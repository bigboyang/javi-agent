package com.agent.metric;

import com.agent.common.DataExporter;
import com.agent.logs.AgentLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Prometheus pull 방식 메트릭 Exporter.
 *
 * <p>내장 HTTP 서버({@code com.sun.net.httpserver.HttpServer})를 이용해
 * {@code /metrics} 엔드포인트를 노출한다. Prometheus 서버가 주기적으로
 * 이 엔드포인트를 스크레이핑(pull)한다.
 *
 * <p>export() 호출마다 최신 MetricData 스냅샷을 교체하고, 스크레이핑 시
 * Prometheus text 0.0.4 포맷으로 직렬화한다.
 *
 * <p>MetricType 매핑:
 * <ul>
 *   <li>GAUGE   → {@code # TYPE <name> gauge}</li>
 *   <li>SUM     → {@code # TYPE <name> counter} + {@code <name>_total} 이름 사용</li>
 *   <li>HISTOGRAM → {@code # TYPE <name> gauge} (count/sum/min/max 개별 지표로 분해된 상태)</li>
 * </ul>
 *
 * <p>설정:
 * <ul>
 *   <li>JAVI_PROMETHEUS_PORT / javi.prometheus.port (기본: 9090)</li>
 * </ul>
 */
public final class PrometheusMetricExporter implements DataExporter<MetricData> {

    private final HttpServer httpServer;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    /**
     * 최신 스냅샷 보관. CopyOnWriteArrayList로 읽기(scrape) 도중 교체(export)에 의한
     * ConcurrentModificationException을 방지한다.
     */
    private volatile List<MetricData> snapshot = new CopyOnWriteArrayList<>();

    // ---- 팩토리 & 생성자 ----

    public static PrometheusMetricExporter create() {
        int port = resolvePort();
        return new PrometheusMetricExporter(port);
    }

    PrometheusMetricExporter(int port) {
        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/metrics", this::handleScrape);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "javi-prometheus-scrape");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            AgentLogger.info("PrometheusMetricExporter HTTP 서버 시작: port=" + port + " path=/metrics");
        } catch (IOException e) {
            AgentLogger.warn("PrometheusMetricExporter 서버 시작 실패: " + e.getMessage());
        }
        this.httpServer = server;
    }

    // ---- DataExporter<MetricData> 구현 ----

    @Override
    public CompletableFuture<Void> export(Collection<MetricData> metrics) {
        if (isShutdown.get() || metrics == null || metrics.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        // 스냅샷 교체: 새 리스트로 atomic 교체 → 읽는 쪽은 volatile로 최신 참조를 얻음
        snapshot = new CopyOnWriteArrayList<>(metrics);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            if (httpServer != null) {
                httpServer.stop(0);
                AgentLogger.info("PrometheusMetricExporter 서버 종료");
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- HTTP 핸들러 ----

    private void handleScrape(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        byte[] body = buildPrometheusText(snapshot).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ---- Prometheus text format 직렬화 ----

    /**
     * MetricData 컬렉션을 Prometheus text 0.0.4 포맷으로 직렬화한다.
     *
     * <p>패키지-프라이빗: 단위 테스트에서 직접 검증 가능.
     */
    static String buildPrometheusText(List<MetricData> metrics) {
        if (metrics == null || metrics.isEmpty()) return "# javi-agent: no metrics\n";

        StringBuilder sb = new StringBuilder(metrics.size() * 256);

        for (MetricData metric : metrics) {
            if (metric == null || metric.getPoints() == null || metric.getPoints().isEmpty()) continue;

            String metricName = sanitizeName(metric.getName());
            MetricData.MetricType type = metric.getType() != null
                    ? metric.getType() : MetricData.MetricType.GAUGE;

            // SUM(Counter)은 Prometheus 관례상 _total suffix
            String exposedName = (type == MetricData.MetricType.SUM)
                    ? metricName + "_total" : metricName;

            sb.append("# TYPE ").append(exposedName).append(" ")
              .append(promType(type)).append("\n");

            for (MetricData.Point point : metric.getPoints()) {
                if (point == null) continue;
                sb.append(exposedName);
                appendLabels(sb, point.getAttributes());
                sb.append(" ").append(formatValue(point.getValue()));
                if (point.getTimestamp() != null) {
                    long epochMs = point.getTimestamp().getEpochSecond() * 1000L
                                 + point.getTimestamp().getNano() / 1_000_000L;
                    sb.append(" ").append(epochMs);
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private static void appendLabels(StringBuilder sb, Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return;
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(sanitizeName(e.getKey()))
              .append("=\"")
              .append(escapeLabelValue(e.getValue()))
              .append("\"");
        }
        sb.append("}");
    }

    /** Prometheus 메트릭/레이블 이름 규칙: [a-zA-Z_:][a-zA-Z0-9_:]* (점은 밑줄로 변환) */
    static String sanitizeName(String name) {
        if (name == null || name.isEmpty()) return "_";
        return name.replace('.', '_').replace('-', '_');
    }

    /** 레이블 값의 이스케이프: \, ", \n */
    private static String escapeLabelValue(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** double 포맷: 정수면 정수형, 아니면 소수 */
    private static String formatValue(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static String promType(MetricData.MetricType type) {
        switch (type) {
            case SUM:       return "counter";
            case HISTOGRAM: return "gauge";  // histogram은 분해된 gauge로 노출
            default:        return "gauge";
        }
    }

    private static int resolvePort() {
        String val = System.getenv("JAVI_PROMETHEUS_PORT");
        if (val == null || val.isEmpty()) val = System.getProperty("javi.prometheus.port", "9090");
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return 9090;
        }
    }
}

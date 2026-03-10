package com.agent.config;

import com.agent.logs.AgentLogger;
import com.agent.sampler.AdaptiveSampler;
import com.agent.sampler.Sampler;
import com.agent.trace.SdkTracerProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 원격 설정 서버를 주기적으로 폴링하여 RemoteConfigHolder를 갱신한다.
 *
 * <p>엔드포인트 설정:
 * <ul>
 *   <li>환경변수: {@code JAVI_REMOTE_CONFIG_URL}
 *   <li>시스템 프로퍼티: {@code javi.remote.config.url}
 *   <li>기본값: 비활성 (URL 없으면 폴링 안 함)
 * </ul>
 *
 * <p>폴링 간격:
 * <ul>
 *   <li>환경변수: {@code JAVI_REMOTE_CONFIG_POLL_INTERVAL_SEC}
 *   <li>기본값: 30초
 * </ul>
 *
 * <p>응답 형식 (flat JSON):
 * <pre>
 * {
 *   "headSampleRate": 0.5,
 *   "tailPolicy": "error,slow,cluster",
 *   "targetTps": 1000,
 *   "logInjection": true,
 *   "metrics": "all",
 *   "spanDrop": "db.statement,http.request.body",
 *   "customHeaders": "x-user-id,x-tenant-id",
 *   "emergencyOff": false,
 *   "serviceDisable": "payment-service,auth-service",
 *   "dropOnFull": true,
 *   "batchSize": 512,
 *   "exportDelay": 5000,
 *   "retryCount": 3,
 *   "serviceWeight": "payment-service:2.0,auth-service:0.5"
 * }
 * </pre>
 */
public final class RemoteConfigPoller {

    private final URI configUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final SdkTracerProvider tracerProvider;
    private volatile AdaptiveSampler adaptiveSampler;
    private ScheduledExecutorService scheduler;

    public RemoteConfigPoller(String configUrl, String apiKey, SdkTracerProvider tracerProvider) {
        this.configUrl = URI.create(configUrl);
        this.apiKey = apiKey;
        
        // 보안 거버넌스: HTTPS 사용 권장 (localhost 제외)
        if (!this.configUrl.getScheme().equalsIgnoreCase("https") 
                && !this.configUrl.getHost().equalsIgnoreCase("localhost")
                && !this.configUrl.getHost().equalsIgnoreCase("127.0.0.1")) {
            AgentLogger.warn("[RemoteConfig] 보안 경고: 원격 설정 URL이 HTTPS가 아닙니다. 중간자 공격에 취약할 수 있습니다.");
        }

        this.tracerProvider = tracerProvider;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "javi-config-http");
                    t.setDaemon(true);
                    return t;
                }))
                .build();
    }

    /**
     * AdaptiveSampler 참조를 등록한다.
     * targetTps / headSampleRate 변경 시 직접 업데이트된다.
     */
    public void setAdaptiveSampler(AdaptiveSampler sampler) {
        this.adaptiveSampler = sampler;
    }

    /**
     * 설정에서 폴링 URL과 간격을 읽어 폴러를 시작한다.
     * URL이 설정되지 않은 경우 비활성 상태로 반환한다.
     *
     * @return 생성된 폴러 인스턴스 (null이면 비활성)
     */
    public static RemoteConfigPoller startIfConfigured(SdkTracerProvider tracerProvider) {
        String url = get("JAVI_REMOTE_CONFIG_URL", "javi.remote.config.url", null);
        if (url == null || url.isEmpty()) {
            AgentLogger.info("[RemoteConfig] URL 미설정 — 원격 설정 비활성");
            return null;
        }
        String apiKey = get("JAVI_REMOTE_CONFIG_API_KEY", "javi.remote.config.api.key", null);
        long intervalSec = parseLong(get("JAVI_REMOTE_CONFIG_POLL_INTERVAL_SEC",
                "javi.remote.config.poll.interval.sec", "30"), 30L);
        RemoteConfigPoller poller = new RemoteConfigPoller(url, apiKey, tracerProvider);
        poller.start(intervalSec);
        AgentLogger.info("[RemoteConfig] 폴링 시작: url=" + url + " interval=" + intervalSec + "s" + (apiKey != null ? " (Auth enabled)" : ""));
        return poller;
    }

    /** 폴링 스케줄러를 시작한다. */
    public void start(long intervalSec) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-config-poller");
            t.setDaemon(true);
            return t;
        });
        // 즉시 1회 폴링 후 주기적으로 반복
        scheduler.scheduleAtFixedRate(this::poll, 0, intervalSec, TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdown();
    }

    // ── 폴링 ─────────────────────────────────────────────────────────────────

    void poll() {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(configUrl)
                    .header("Accept", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(5));
            
            if (apiKey != null && !apiKey.isEmpty()) {
                reqBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpRequest request = reqBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                RemoteConfig config = parse(response.body());
                if (RemoteConfigValidator.isValid(config)) {
                    applyConfig(config);
                    AgentLogger.debug("[RemoteConfig] 갱신 완료: emergencyOff=" + config.isEmergencyOff()
                            + " headSampleRate=" + config.getHeadSampleRate()
                            + " targetTps=" + config.getTargetTps());
                } else {
                    AgentLogger.warn("[RemoteConfig] 폴링 중단: 부적절한 설정값 수신");
                }
            } else {
                AgentLogger.warn("[RemoteConfig] 폴링 실패: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            AgentLogger.warn("[RemoteConfig] 폴링 오류: " + e.getMessage());
        }
    }

    private void applyConfig(RemoteConfig newConfig) {
        RemoteConfig prev = RemoteConfigHolder.get();
        RemoteConfigHolder.update(newConfig);

        // 유효 샘플링 비율 = headSampleRate × 서비스 가중치
        double effectiveRate = computeEffectiveRate(newConfig);

        // AdaptiveSampler 동적 업데이트
        AdaptiveSampler sampler = this.adaptiveSampler;
        if (sampler != null) {
            boolean rateChanged = Double.compare(prev.getHeadSampleRate(), newConfig.getHeadSampleRate()) != 0;
            boolean tpsChanged = prev.getTargetTps() != newConfig.getTargetTps();
            if (rateChanged || tpsChanged) {
                sampler.update(newConfig.getTargetTps(), effectiveRate);
                AgentLogger.info("[RemoteConfig] AdaptiveSampler 업데이트: effectiveRate="
                        + effectiveRate + " targetTps=" + newConfig.getTargetTps());
            }
        } else if (tracerProvider != null
                && Double.compare(prev.getHeadSampleRate(), newConfig.getHeadSampleRate()) != 0) {
            // AdaptiveSampler가 아닌 경우 새 TraceIdRatio 샘플러로 교체
            Sampler newSampler = com.agent.sampler.Sampler.traceIdRatioBased(effectiveRate);
            tracerProvider.setSampler(newSampler);
            AgentLogger.info("[RemoteConfig] Sampler 교체: effectiveRate=" + effectiveRate);
        }
    }

    /** headSampleRate에 서비스별 가중치를 곱해 유효 샘플링 비율을 계산한다. */
    private static double computeEffectiveRate(RemoteConfig config) {
        String serviceName = AgentConfig.load().getServiceName();
        Double weight = config.getServiceWeight().get(serviceName);
        if (weight == null) return config.getHeadSampleRate();
        return Math.max(0.0, Math.min(1.0, config.getHeadSampleRate() * weight));
    }

    // ── JSON 파싱 ─────────────────────────────────────────────────────────────

    /** 플랫 JSON에서 RemoteConfig를 파싱한다. */
    static RemoteConfig parse(String json) {
        if (json == null || json.isEmpty()) return RemoteConfig.defaults();
        try {
            RemoteConfig.Builder b = RemoteConfig.builder();

            // 숫자/불리언 primitives
            String headSampleRate = extractPrimitive(json, "headSampleRate");
            if (headSampleRate != null) b.headSampleRate(parseDouble(headSampleRate, 1.0));

            String targetTps = extractPrimitive(json, "targetTps");
            if (targetTps != null) b.targetTps(parseLong(targetTps, 0L));

            String logInjection = extractPrimitive(json, "logInjection");
            if (logInjection != null) b.logInjection(Boolean.parseBoolean(logInjection));

            String emergencyOff = extractPrimitive(json, "emergencyOff");
            if (emergencyOff != null) b.emergencyOff(Boolean.parseBoolean(emergencyOff));

            String dropOnFull = extractPrimitive(json, "dropOnFull");
            if (dropOnFull != null) b.dropOnFull(Boolean.parseBoolean(dropOnFull));

            String batchSize = extractPrimitive(json, "batchSize");
            if (batchSize != null) b.batchSize((int) parseLong(batchSize, 512L));

            String exportDelay = extractPrimitive(json, "exportDelay");
            if (exportDelay != null) b.exportDelay(parseLong(exportDelay, 5000L));

            String retryCount = extractPrimitive(json, "retryCount");
            if (retryCount != null) b.retryCount((int) parseLong(retryCount, 3L));

            // 문자열 primitives
            String metrics = extractString(json, "metrics");
            if (metrics != null) b.metrics(metrics);

            // 콤마 구분 집합/목록
            String tailPolicyRaw = extractString(json, "tailPolicy");
            if (tailPolicyRaw != null) b.tailPolicy(parseSet(tailPolicyRaw));

            String spanDropRaw = extractString(json, "spanDrop");
            if (spanDropRaw != null) b.spanDrop(parseSet(spanDropRaw));

            String customHeadersRaw = extractString(json, "customHeaders");
            if (customHeadersRaw != null) b.customHeaders(parseList(customHeadersRaw));

            String serviceDisableRaw = extractString(json, "serviceDisable");
            if (serviceDisableRaw != null) b.serviceDisable(parseSet(serviceDisableRaw));

            // 서비스 가중치: "svc1:1.5,svc2:0.5"
            String serviceWeightRaw = extractString(json, "serviceWeight");
            if (serviceWeightRaw != null) b.serviceWeight(parseServiceWeight(serviceWeightRaw));

            return b.build();
        } catch (Throwable t) {
            AgentLogger.warn("[RemoteConfig] 파싱 오류 — 기본값 사용: " + t.getMessage());
            return RemoteConfig.defaults();
        }
    }

    // ── 파싱 유틸 ─────────────────────────────────────────────────────────────

    /** "key": "value" 추출 */
    private static String extractString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** "key": value (숫자 또는 불리언) 추출 */
    private static String extractPrimitive(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([^,}\\s\"]+)");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1).trim() : null;
    }

    private static Set<String> parseSet(String raw) {
        Set<String> result = new HashSet<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private static List<String> parseList(String raw) {
        List<String> result = new ArrayList<>();
        for (String s : raw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** "svc1:1.5,svc2:0.5" 형식을 Map으로 변환 */
    private static Map<String, Double> parseServiceWeight(String raw) {
        Map<String, Double> result = new HashMap<>();
        for (String entry : raw.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length == 2) {
                try {
                    result.put(parts[0].trim(), Double.parseDouble(parts[1].trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }
}

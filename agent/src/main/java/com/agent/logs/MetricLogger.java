package com.agent.logs;

import com.agent.metric.Counter;
import com.agent.metric.Gauge;
import com.agent.metric.Histogram;
import com.agent.metric.MetricRegistry;
import com.agent.metric.MetricKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * MetricRegistry에 등록된 메트릭을 주기적으로 파일에 기록하는 로거.
 *
 * <p>설정 우선순위: 환경변수 > 시스템 프로퍼티 > 기본값
 * <ul>
 *   <li>JAVI_METRIC_LOG_FILE / javi.metric.log.file — 파일 경로 (기본: javi/logs/javi-metrics.log)</li>
 *   <li>JAVI_METRIC_INTERVAL_SEC / javi.metric.interval.sec — export 주기 초 (기본: 60)</li>
 * </ul>
 *
 * <p>출력 포맷:
 * <pre>
 * [2025-01-01 12:00:00.000] [METRIC] name=http.server.request.count type=counter value=42
 * [2025-01-01 12:00:00.000] [METRIC] name=http.server.request.duration type=histogram count=42 sum=1234 min=5 max=120 avg=29.38
 * [2025-01-01 12:00:00.000] [METRIC] name=jvm.memory.heap.used type=gauge value=104857600
 * </pre>
 */
public final class MetricLogger {

    private static final Logger LOGGER;
    private static final String LOG_FILE_PATH;
    private static volatile ScheduledExecutorService executor;

    static {
        String logFile = get("JAVI_METRIC_LOG_FILE", "javi.metric.log.file", "javi/logs/javi-metrics.log");
        LOG_FILE_PATH = logFile;

        LOGGER = Logger.getLogger("com.agent.javi.metrics");
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.ALL);

        try {
            Path logPath = Paths.get(logFile);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            int limitBytes = parseInt(get("JAVI_METRIC_LOG_FILE_LIMIT_BYTES", "javi.metric.log.file.limit.bytes", "20971520"), 20971520);
            int fileCount = parseInt(get("JAVI_METRIC_LOG_FILE_COUNT", "javi.metric.log.file.count", "5"), 5);
            FileHandler fileHandler = new FileHandler(logFile, limitBytes, fileCount, true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new MetricFormatter());
            LOGGER.addHandler(fileHandler);
        } catch (IOException e) {
            AgentLogger.warn("[MetricLogger] 메트릭 로그 파일 초기화 실패 (" + logFile + "): " + e.getMessage());
        }
    }

    private MetricLogger() {}

    /**
     * 주기적 메트릭 export를 시작한다.
     *
     * @param intervalSec export 간격 (초)
     */
    public static void start(int intervalSec) {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "javi-metric-logger");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(MetricLogger::exportAll, intervalSec, intervalSec, TimeUnit.SECONDS);
        AgentLogger.info("MetricLogger 시작 (" + intervalSec + "초 간격) → " + LOG_FILE_PATH);
    }

    public static void start() {
        int intervalSec = parseInt(
                get("JAVI_METRIC_INTERVAL_SEC", "javi.metric.interval.sec", "60"), 60);
        start(intervalSec);
    }

    public static void stop() {
        if (executor != null) executor.shutdown();
    }

    /** 현재 모든 메트릭을 즉시 파일에 기록한다. */
    public static void exportAll() {
        MetricRegistry reg = MetricRegistry.get();
        for (Map.Entry<MetricKey, Counter> e : reg.counters().entrySet()) {
            LOGGER.info("name=" + e.getKey().getName()
                    + " attrs=" + e.getKey().getAttributes()
                    + " type=counter"
                    + " value=" + e.getValue().get());
        }
        for (Map.Entry<String, Histogram> e : reg.histograms().entrySet()) {
            Histogram h = e.getValue();
            LOGGER.info("name=" + e.getKey()
                    + " attrs={}"
                    + " type=histogram"
                    + " count=" + h.getCount()
                    + " sum=" + h.getSum());
        }
        for (Map.Entry<MetricKey, Gauge> e : reg.gauges().entrySet()) {
            LOGGER.info("name=" + e.getKey().getName()
                    + " attrs=" + e.getKey().getAttributes()
                    + " type=gauge"
                    + " value=" + e.getValue().get());
        }
    }

    public static void log(String message) {
        LOGGER.info(message);
    }

    public static String logFilePath() {
        return LOG_FILE_PATH;
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    private static int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final class MetricFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(record.getMillis());
            String time = java.time.ZonedDateTime
                    .ofInstant(instant, java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            return '[' + time + "] [METRIC] " + formatMessage(record) + System.lineSeparator();
        }
    }
}

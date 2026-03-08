package com.agent.metric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 메트릭 데이터 전용 로거.
 * 수집된 메트릭을 javi/logs/javi-metrics.log 파일에 별도로 기록한다.
 */
public final class MetricLogger {

    private static final Logger LOGGER;
    private static final String LOG_FILE_PATH;

    static {
        String logFile = get("JAVI_METRIC_FILE", "javi.metric.file", "javi/logs/javi-metrics.log");
        LOG_FILE_PATH = logFile;

        LOGGER = Logger.getLogger("com.agent.javi.metrics");
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.INFO);

        try {
            Path logPath = Paths.get(logFile);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            FileHandler fileHandler = new FileHandler(logFile, true);
            fileHandler.setFormatter(new MetricFormatter());
            LOGGER.addHandler(fileHandler);
        } catch (IOException e) {
            System.err.println("[javi-agent] 메트릭 로그 초기화 실패: " + e.getMessage());
        }
    }

    private MetricLogger() {}

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

    /** 메트릭 전용 포맷터: [시간] 메시지 */
    private static final class MetricFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(record.getMillis());
            String time = java.time.ZonedDateTime
                    .ofInstant(instant, java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            return "[" + time + "] " + formatMessage(record) + System.lineSeparator();
        }
    }
}

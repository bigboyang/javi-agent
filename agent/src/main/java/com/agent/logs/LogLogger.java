package com.agent.logs;

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
 * 수집된 로그 데이터 전용 로거.
 * javi/logs/javi-logs.log 파일에 수집된 로그 레코드를 기록한다.
 */
public final class LogLogger {

    private static final Logger LOGGER;
    private static final String LOG_FILE_PATH;

    static {
        String logFile = get("JAVI_LOG_DATA_FILE", "javi.log.data.file", "javi/logs/javi-logs.log");
        LOG_FILE_PATH = logFile;

        LOGGER = Logger.getLogger("com.agent.javi.logs.data");
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.INFO);

        try {
            Path logPath = Paths.get(logFile);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            int limitBytes = parseInt(get("JAVI_LOG_DATA_FILE_LIMIT_BYTES", "javi.log.data.file.limit.bytes", "52428800"), 52428800);
            int fileCount = parseInt(get("JAVI_LOG_DATA_FILE_COUNT", "javi.log.data.file.count", "5"), 5);
            FileHandler fileHandler = new FileHandler(logFile, limitBytes, fileCount, true);
            fileHandler.setFormatter(new LogDataFormatter());
            LOGGER.addHandler(fileHandler);
        } catch (IOException e) {
            System.err.println("[javi-agent] 로그 데이터 파일 초기화 실패: " + e.getMessage());
        }
    }

    private LogLogger() {}

    public static void log(String message) {
        LOGGER.info(message);
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final class LogDataFormatter extends Formatter {
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

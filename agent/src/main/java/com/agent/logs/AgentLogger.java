package com.agent.logs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * javi-agent 내부 로거.
 *
 * <p>JDK 내장 java.util.logging을 사용해 외부 의존성 없이 콘솔 + 파일 로그를 동시에 출력한다.
 *
 * <p>설정 우선순위: 환경변수 > 시스템 프로퍼티 > 기본값
 * <ul>
 *   <li>JAVI_LOG_FILE / javi.log.file — 로그 파일 경로 (기본: javi/logs/javi-agent.log)</li>
 *   <li>JAVI_LOG_LEVEL / javi.log.level — 로그 레벨 (기본: FINE)</li>
 * </ul>
 */
public final class AgentLogger {

    private static final Logger LOGGER;
    private static final String LOG_FILE_PATH;

    static {
        String logFile = get("JAVI_LOG_FILE", "javi.log.file", "javi/logs/javi-agent.log");
        String logLevel = get("JAVI_LOG_LEVEL", "javi.log.level", "FINE");
        LOG_FILE_PATH = logFile;

        LOGGER = Logger.getLogger("com.agent.javi");
        LOGGER.setUseParentHandlers(false);

        Level level;
        try {
            level = Level.parse(logLevel.toUpperCase());
        } catch (IllegalArgumentException e) {
            level = Level.INFO;
        }
        LOGGER.setLevel(level);

        // 콘솔 핸들러
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(level);
        consoleHandler.setFormatter(new AgentFormatter());
        LOGGER.addHandler(consoleHandler);

        // 파일 핸들러
        try {
            Path logPath = Paths.get(logFile);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            // append=true, 단일 파일 (rotate 없음)
            FileHandler fileHandler = new FileHandler(logFile, true);
            fileHandler.setLevel(level);
            fileHandler.setFormatter(new AgentFormatter());
            LOGGER.addHandler(fileHandler);
        } catch (IOException e) {
            LOGGER.warning("[javi-agent] 로그 파일 초기화 실패 (" + logFile + "): " + e.getMessage());
        }
    }

    private AgentLogger() {}

    public static void info(String message) {
        LOGGER.info(message);
    }

    public static void warn(String message) {
        LOGGER.warning(message);
    }

    public static void error(String message) {
        LOGGER.severe(message);
    }

    public static void error(String message, Throwable t) {
        LOGGER.log(Level.SEVERE, message, t);
    }

    public static void debug(String message) {
        LOGGER.fine(message);
    }

    /** 현재 로그 파일 경로를 반환한다. */
    public static String logFilePath() {
        return LOG_FILE_PATH;
    }

    /** 모든 핸들러를 flush한다. */
    public static void flush() {
        for (Handler handler : LOGGER.getHandlers()) {
            handler.flush();
        }
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    /** 단순 텍스트 포맷터: [yyyy-MM-dd HH:mm:ss] [LEVEL] message */
    private static final class AgentFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(record.getMillis());
            String time = java.time.ZonedDateTime
                    .ofInstant(instant, java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            String level = record.getLevel().getName();
            String message = formatMessage(record);

            StringBuilder sb = new StringBuilder();
            sb.append('[').append(time).append(']')
              .append(" [").append(level).append(']')
              .append(" ").append(message)
              .append(System.lineSeparator());

            if (record.getThrown() != null) {
                sb.append(stackTraceToString(record.getThrown()));
            }
            return sb.toString();
        }

        private String stackTraceToString(Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return sw.toString();
        }
    }
}

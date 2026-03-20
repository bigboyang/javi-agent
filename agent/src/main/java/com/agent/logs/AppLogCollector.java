package com.agent.logs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 대상 애플리케이션의 java.util.logging 로그를 캡처해 trace context를 첨부하는 컬렉터.
 *
 * <p>JUL root logger에 Handler를 설치해 INFO 이상의 앱 로그를 intercept한다.
 * 현재 스레드의 span context(traceId, spanId)를 자동으로 첨부하여
 * 로그와 분산 트레이스를 연결할 수 있다.
 *
 * <p>설정 우선순위: 환경변수 > 시스템 프로퍼티 > 기본값
 * <ul>
 *   <li>JAVI_APP_LOG_FILE / javi.app.log.file — 캡처 로그 파일 경로 (기본: javi/logs/javi-app-logs.log)</li>
 * </ul>
 *
 * <p>출력 포맷:
 * <pre>
 * [2025-01-01 12:00:00.000] [INFO] [traceId=xxx spanId=yyy] [com.example.UserService] 메시지
 * </pre>
 */
public final class AppLogCollector {

    private static final String LOG_FILE_PATH;
    private static volatile boolean installed = false;

    static {
        LOG_FILE_PATH = get("JAVI_APP_LOG_FILE", "javi.app.log.file", "javi/logs/javi-app-logs.log");
    }

    /** JAVI_LOG_MIN_LEVEL에서 JUL Level을 파생한다. */
    private static Level resolveJulLevel() {
        String minLevel = get("JAVI_LOG_MIN_LEVEL", "javi.log.min.level", "INFO").toUpperCase();
        switch (minLevel) {
            case "TRACE": return Level.FINEST;
            case "DEBUG": return Level.FINE;
            case "WARN": case "WARNING": return Level.WARNING;
            case "ERROR": case "SEVERE": return Level.SEVERE;
            default: return Level.INFO;
        }
    }

    private AppLogCollector() {}

    /**
     * JUL root logger에 AppLogHandler를 설치한다.
     * 중복 설치 방지를 위해 한 번만 실행된다.
     */
    public static synchronized void install() {
        if (installed) return;
        try {
            AppLogHandler handler = new AppLogHandler(LOG_FILE_PATH);
            Logger.getLogger("").addHandler(handler);
            installed = true;
            AgentLogger.info("AppLogCollector 설치 완료 → " + LOG_FILE_PATH);
        } catch (IOException e) {
            AgentLogger.warn("[AppLogCollector] 핸들러 설치 실패: " + e.getMessage());
        }
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

    /**
     * 앱 로그를 캡처하는 JUL Handler.
     *
     * <p>agent 내부 로거(com.agent.javi*)는 제외하여 무한 루프를 방지한다.
     */
    private static final class AppLogHandler extends Handler {

        private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);
        private final FileHandler fileHandler;

        AppLogHandler(String logFile) throws IOException {
            Path logPath = Paths.get(logFile);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            this.fileHandler = new FileHandler(logFile, true);
            this.fileHandler.setFormatter(new AppLogFormatter());
            setLevel(resolveJulLevel());
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) return;

            // agent 내부 로거 제외 (무한루프 방지)
            String loggerName = record.getLoggerName();
            if (loggerName != null && loggerName.startsWith("com.agent.javi")) return;

            // 재진입 방지
            if (Boolean.TRUE.equals(PUBLISHING.get())) return;
            PUBLISHING.set(true);
            try {
                fileHandler.publish(record);
                // Bug #9: JUL 로그를 OTLP 파이프라인에도 전달 — ClickHouse에서 JUL 로그 수집
                String body = formatJulBody(record);
                SdkLogEmitter.emit(loggerName, julLevelToSeverity(record.getLevel()), body,
                        java.util.Collections.emptyMap());
            } finally {
                PUBLISHING.set(false);
            }
        }

        /** JUL Level → OTel severity 문자열 변환. */
        private static String julLevelToSeverity(Level level) {
            if (level == null) return "INFO";
            int v = level.intValue();
            if (v >= Level.SEVERE.intValue())  return "ERROR";
            if (v >= Level.WARNING.intValue()) return "WARN";
            if (v >= Level.INFO.intValue())    return "INFO";
            if (v >= Level.FINE.intValue())    return "DEBUG";
            return "TRACE";
        }

        /**
         * JUL LogRecord 메시지를 파라미터 치환하여 반환한다.
         * {0}, {1} 플레이스홀더가 있는 경우 MessageFormat으로 포맷한다.
         */
        private static String formatJulBody(LogRecord record) {
            String raw = record.getMessage();
            if (raw == null) return "";
            Object[] params = record.getParameters();
            if (params == null || params.length == 0) return raw;
            try {
                return java.text.MessageFormat.format(raw, params);
            } catch (Exception e) {
                return raw;
            }
        }

        @Override
        public void flush() {
            fileHandler.flush();
        }

        @Override
        public void close() {
            fileHandler.close();
        }
    }

    /**
     * 앱 로그 포맷터: [timestamp] [LEVEL] [traceId=X spanId=Y] [loggerName] message
     */
    private static final class AppLogFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(record.getMillis());
            String time = java.time.ZonedDateTime
                    .ofInstant(instant, java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));

            String level = record.getLevel().getName();
            String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "unknown";
            String message = formatMessage(record);

            // 현재 스레드의 span context에서 traceId/spanId 추출
            String traceContext = extractTraceContext();

            StringBuilder sb = new StringBuilder();
            sb.append('[').append(time).append(']')
              .append(" [").append(level).append(']')
              .append(traceContext)
              .append(" [").append(loggerName).append(']')
              .append(' ').append(message)
              .append(System.lineSeparator());

            if (record.getThrown() != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                record.getThrown().printStackTrace(new java.io.PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }

        private String extractTraceContext() {
            try {
                com.agent.span.SpanContext ctx =
                        com.agent.span.Context.currentSpan().getContext();
                if (ctx != null && ctx.isValid()) {
                    return " [traceId=" + ctx.getTraceId() + " spanId=" + ctx.getSpanId() + ']';
                }
            } catch (Throwable ignored) {}
            return "";
        }
    }
}

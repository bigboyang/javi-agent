package com.agent.logs;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 대상 애플리케이션 로그(JUL / Logback / Log4j2)를 캡처해 파일 및 OTLP 파이프라인으로 전달하는 컬렉터.
 *
 * <ul>
 *   <li>JUL  — JUL root logger에 {@link AppLogHandler} 설치</li>
 *   <li>Logback / Log4j2 — ByteBuddy Advice(LogbackAdvice, Log4j2Advice)가 {@link #handleLogEvent}를 호출</li>
 * </ul>
 *
 * <p>세 경로 모두 {@link #writeLine} → 공유 파일, {@link SdkLogEmitter#emit} → OTLP 순서로 처리한다.
 * 공유 {@link PrintWriter}({@code sharedWriter})가 null이어도 OTLP 경로는 동작한다.
 *
 * <p>설정 우선순위: 환경변수 > 시스템 프로퍼티 > 기본값
 * <ul>
 *   <li>JAVI_APP_LOG_FILE / javi.app.log.file — 캡처 로그 파일 경로 (기본: javi/logs/javi-app-logs.log)</li>
 *   <li>JAVI_LOG_MIN_LEVEL / javi.log.min.level — 최소 로그 레벨 (기본: INFO)</li>
 * </ul>
 */
public final class AppLogCollector {

    private static final String LOG_FILE_PATH;
    private static volatile boolean installed = false;

    /** 모든 로그 소스(JUL, Logback, Log4j2)가 공유하는 파일 라이터. null이면 파일 기록 생략. */
    private static volatile PrintWriter sharedWriter;

    /** 재진입·크로스-프레임워크 루프 방지 */
    static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    static {
        LOG_FILE_PATH = get("JAVI_APP_LOG_FILE", "javi.app.log.file", "javi/logs/javi-app-logs.log");
    }

    private AppLogCollector() {}

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

    /**
     * JUL root logger에 핸들러를 설치하고 공유 파일 라이터를 초기화한다.
     *
     * <p>파일 생성에 실패해도 {@code installed = true}로 설정하여
     * Logback/Log4j2의 OTLP 경로가 계속 동작하도록 한다.
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            Path logPath = Paths.get(LOG_FILE_PATH);
            if (logPath.getParent() != null) Files.createDirectories(logPath.getParent());
            sharedWriter = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(LOG_FILE_PATH, true), StandardCharsets.UTF_8)));
            Logger.getLogger("").addHandler(new AppLogHandler());
            AgentLogger.info("AppLogCollector 설치 완료 → " + LOG_FILE_PATH);
        } catch (IOException e) {
            AgentLogger.warn("[AppLogCollector] 파일 핸들러 설치 실패 (OTLP 수집은 계속 동작): " + e.getMessage());
        }
    }

    /**
     * Logback·Log4j2 로그 이벤트 캡처 진입점.
     *
     * <p>LogbackAdvice·Log4j2Advice가 ByteBuddy 인라인으로 호출한다.
     * 파일 기록 + OTLP 전송을 수행하며 agent 내부 로거와 재진입은 건너뛴다.
     *
     * @param loggerName 로거 이름 (null 허용)
     * @param level      severity 문자열 (INFO, WARN, ERROR, DEBUG, TRACE)
     * @param message    포맷 완료된 메시지 (null 허용)
     * @param attrs      스레드·예외 정보 맵 (null 허용)
     */
    public static void handleLogEvent(String loggerName, String level, String message, Map<String, String> attrs) {
        if (!installed) return;
        if (loggerName != null && loggerName.startsWith("com.agent.javi")) return;
        if (Boolean.TRUE.equals(PUBLISHING.get())) return;
        PUBLISHING.set(true);
        try {
            writeLine(formatLine(loggerName, level, message, attrs));
            SdkLogEmitter.emit(loggerName, level, message, attrs != null ? attrs : Collections.emptyMap());
        } finally {
            PUBLISHING.set(false);
        }
    }

    public static String logFilePath() {
        return LOG_FILE_PATH;
    }

    private static synchronized void writeLine(String line) {
        PrintWriter w = sharedWriter;
        if (w == null) return;
        w.println(line);
        w.flush();
    }

    private static String formatLine(String loggerName, String level, String message, Map<String, String> attrs) {
        String time = ZonedDateTime.now(ZoneId.systemDefault()).format(TIME_FMT);
        String traceCtx = extractTraceContext();
        String name = loggerName != null ? loggerName : "unknown";
        String msg  = message != null ? message : "";
        String lvl  = level != null ? level : "INFO";

        StringBuilder sb = new StringBuilder(256);
        sb.append('[').append(time).append(']')
          .append(" [").append(lvl).append(']')
          .append(traceCtx)
          .append(" [").append(name).append(']')
          .append(' ').append(msg);

        if (attrs != null) {
            String stacktrace = attrs.get("exception.stacktrace");
            if (stacktrace != null && !stacktrace.isEmpty()) {
                sb.append(System.lineSeparator()).append(stacktrace);
            }
        }
        return sb.toString();
    }

    private static String extractTraceContext() {
        try {
            com.agent.span.SpanContext ctx = com.agent.span.Context.currentSpan().getContext();
            if (ctx != null && ctx.isValid()) {
                return " [traceId=" + ctx.getTraceId() + " spanId=" + ctx.getSpanId() + ']';
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String get(String envKey, String propKey, String defaultValue) {
        String val = System.getenv(envKey);
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    /**
     * JUL 로그를 캡처하는 핸들러.
     *
     * <p>agent 내부 로거(com.agent.javi*)는 제외하여 무한 루프를 방지한다.
     * 파일 기록은 outer class의 {@link #writeLine}을 통해 공유 라이터에 위임한다.
     */
    private static final class AppLogHandler extends Handler {

        AppLogHandler() {
            setLevel(resolveJulLevel());
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) return;
            String loggerName = record.getLoggerName();
            if (loggerName != null && loggerName.startsWith("com.agent.javi")) return;
            if (Boolean.TRUE.equals(PUBLISHING.get())) return;
            PUBLISHING.set(true);
            try {
                String level = julLevelToSeverity(record.getLevel());
                String body  = formatJulBody(record);
                Map<String, String> attrs = buildJulAttributes(record);
                writeLine(formatLine(loggerName, level, body, attrs));
                SdkLogEmitter.emit(loggerName, level, body, attrs);
            } finally {
                PUBLISHING.set(false);
            }
        }

        @Override
        public void flush() {
            synchronized (AppLogCollector.class) {
                PrintWriter w = sharedWriter;
                if (w != null) w.flush();
            }
        }

        @Override
        public void close() {
            synchronized (AppLogCollector.class) {
                PrintWriter w = sharedWriter;
                if (w != null) {
                    w.flush();
                    w.close();
                    sharedWriter = null;
                }
            }
        }
    }

    private static Map<String, String> buildJulAttributes(LogRecord record) {
        Map<String, String> attrs = new HashMap<>(6);
        attrs.put("thread.name", Thread.currentThread().getName());
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            attrs.put("exception.type", thrown.getClass().getName());
            String msg = thrown.getMessage();
            if (msg != null && !msg.isEmpty()) attrs.put("exception.message", msg);
            StringWriter sw = new StringWriter(512);
            thrown.printStackTrace(new PrintWriter(sw));
            attrs.put("exception.stacktrace", sw.toString());
        }
        return attrs;
    }

    private static String julLevelToSeverity(Level level) {
        if (level == null) return "INFO";
        int v = level.intValue();
        if (v >= Level.SEVERE.intValue())  return "ERROR";
        if (v >= Level.WARNING.intValue()) return "WARN";
        if (v >= Level.INFO.intValue())    return "INFO";
        if (v >= Level.FINE.intValue())    return "DEBUG";
        return "TRACE";
    }

    private static String formatJulBody(LogRecord record) {
        String raw = record.getMessage();
        if (raw == null) return "";
        Object[] params = record.getParameters();
        if (params == null || params.length == 0) return raw;
        try {
            return MessageFormat.format(raw, params);
        } catch (Exception e) {
            return raw;
        }
    }
}

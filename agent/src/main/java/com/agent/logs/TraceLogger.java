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
 * APM span/trace export 전용 로거.
 *
 * <p>AgentLogger(내부 운영 로그)와 분리하여 수집된 trace 데이터를 별도 파일에
 * MDC(Mapped Diagnostic Context) 형태의 구조화된 필드로 기록한다.
 *
 * <p>설정 우선순위: 환경변수 > 시스템 프로퍼티 > 기본값
 * <ul>
 *   <li>JAVI_TRACE_LOG_FILE / javi.trace.log.file — trace 로그 파일 경로 (기본: javi/logs/javi-traces.log)</li>
 *   <li>JAVI_SERVICE_NAME / javi.service.name     — service 식별자 (MDC 필드로 포함)</li>
 * </ul>
 *
 * <p>로그 포맷 (MDC 구조):
 * <pre>
 * [2025-01-01 12:00:00.000] [TRACE] service=my-svc traceId=xxx spanId=yyy parentSpanId=zzz
 *     operation=GET /api/users kind=SERVER durationMs=12 status=OK attrs={http.method=GET}
 * </pre>
 */
public final class TraceLogger {

    private static final Logger LOGGER;
    private static final String LOG_FILE_PATH;

    /** MDC: 서비스 이름. AgentRuntime 초기화 시 주입된다. */
    private static volatile String serviceName = "javi-service";

    static {
        String logFile = get("JAVI_TRACE_LOG_FILE", "javi.trace.log.file", "javi/logs/javi-traces.log");
        LOG_FILE_PATH = logFile;

        LOGGER = Logger.getLogger("com.agent.javi.traces");
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.ALL);

        try {
            Path logPath = Paths.get(logFile);
            if (logPath.getParent() != null) {
                Files.createDirectories(logPath.getParent());
            }
            FileHandler fileHandler = new FileHandler(logFile, true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(new TraceFormatter());
            LOGGER.addHandler(fileHandler);
        } catch (IOException e) {
            AgentLogger.warn("[TraceLogger] trace 로그 파일 초기화 실패 (" + logFile + "): " + e.getMessage());
        }
    }

    private TraceLogger() {}

    /**
     * service 이름을 MDC 컨텍스트에 설정한다.
     * AgentRuntime 초기화 시 AgentConfig에서 읽은 값으로 호출해야 한다.
     */
    public static void setServiceName(String name) {
        if (name != null && !name.isEmpty()) {
            serviceName = name;
        }
    }

    /**
     * span 데이터를 MDC 구조로 trace 로그에 기록한다.
     *
     * @param traceId     W3C trace ID
     * @param spanId      span ID
     * @param traceFlags  W3C trace flags (샘플링 여부: 01=sampled, 00=not)
     * @param parentSpanId parent span ID (없으면 "0000000000000000")
     * @param operation   span 이름 (e.g. "GET /api/users")
     * @param kind        SpanKind (SERVER / CLIENT / INTERNAL 등)
     * @param durationMs  span 소요 시간 (밀리초)
     * @param status      span 종료 상태 (OK / ERROR / UNSET)
     * @param attrs       span attributes 문자열 표현 (없으면 null)
     */
    public static void trace(String traceId, String spanId, String traceFlags,
                             String parentSpanId, String operation, String kind,
                             long durationMs, String status, String attrs) {
        StringBuilder mdc = new StringBuilder();
        mdc.append("service=").append(serviceName)
           .append(" traceId=").append(traceId)
           .append(" spanId=").append(spanId)
           .append(" traceFlags=").append(traceFlags)
           .append(" parentSpanId=").append(parentSpanId)
           .append(" operation=").append(operation)
           .append(" kind=").append(kind)
           .append(" durationMs=").append(durationMs)
           .append(" status=").append(status);
        if (attrs != null && !attrs.isEmpty()) {
            mdc.append(" attrs=").append(attrs);
        }
        LOGGER.info(mdc.toString());
    }

    /** 현재 trace 로그 파일 경로를 반환한다. */
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

    /** trace 전용 포맷터: [yyyy-MM-dd HH:mm:ss.SSS] [TRACE] {MDC key=value ...} */
    private static final class TraceFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(record.getMillis());
            String time = java.time.ZonedDateTime
                    .ofInstant(instant, java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
            return '[' + time + "] [TRACE] " + formatMessage(record) + System.lineSeparator();
        }
    }
}

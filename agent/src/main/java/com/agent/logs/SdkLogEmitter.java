package com.agent.logs;


import com.agent.common.JaviSdk;
import com.agent.span.Context;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 에이전트 내 로그 수집 전용 엔드포인트.
 * JaviSdk의 LoggerProvider를 통해 로그를 수집한다.
 */
public final class SdkLogEmitter {

    private static volatile String serviceName = "javi-service";

    public static void setServiceName(String name) {
        serviceName = name;
    }

    /**
     * 로그 이벤트를 캡처하여 파이프라인에 넣는다.
     */
    public static void emit(String loggerName, String level, String message, Map<String, String> attributes) {
        try {
            JaviSdk sdk = JaviSdk.get();
            if (sdk == null) return;

            Span currentSpan = Context.currentSpan();
            SpanContext ctx = currentSpan.getContext();
            
            String traceId = "";
            String spanId = "";
            if (ctx != null && ctx.isValid()) {
                traceId = ctx.getTraceId();
                spanId = ctx.getSpanId();
            }

            LogRecord record = new LogRecord(
                    Instant.now(),
                    level,
                    message,
                    traceId,
                    spanId,
                    loggerName,
                    attributes != null ? attributes : Collections.emptyMap()
            );

            sdk.getLoggerProvider().emit(record);

        } catch (Throwable ignored) {
        }
    }

    /** 종료 시 호출 */
    public static void shutdown() {
        // 전역 shutdown에서 처리됨
    }
}

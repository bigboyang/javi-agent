package com.agent.logs;


import com.agent.common.JaviSdk;
import com.agent.propagation.Baggage;
import com.agent.span.Context;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 에이전트 내 로그 수집 전용 엔드포인트.
 * JaviSdk의 LoggerProvider를 통해 로그를 수집한다.
 */
public final class SdkLogEmitter {

    /**
     * 로그 이벤트를 캡처하여 파이프라인에 넣는다.
     *
     * <p>LogSampler를 통해 레벨 필터링, 샘플링, 속도 제한을 적용한 뒤 통과한 로그만 전송한다.
     */
    public static void emit(String loggerName, String level, String message, Map<String, String> attributes) {
        try {
            // 레벨 필터 + 샘플링 + 속도 제한 — 드랍 결정은 최대한 빠르게 처리
            if (!LogSampler.INSTANCE.shouldSample(level)) return;

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

            Baggage baggage = Context.currentBaggage();
            Map<String, String> mergedAttributes;
            if (baggage.isEmpty()) {
                mergedAttributes = attributes != null ? attributes : Collections.emptyMap();
            } else {
                mergedAttributes = new HashMap<>(baggage.asMap());
                if (attributes != null) {
                    mergedAttributes.putAll(attributes);
                }
            }

            LogRecord record = new LogRecord(
                    Instant.now(),
                    level,
                    message,
                    traceId,
                    spanId,
                    loggerName,
                    mergedAttributes
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

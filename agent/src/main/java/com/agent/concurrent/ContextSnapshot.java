package com.agent.concurrent;

import java.util.HashMap;
import java.util.Map;

/**
 * 비동기/멀티쓰레드 환경에서 span context를 캡처/복원하는 유틸리티.
 *
 * Bootstrap classloader 안전 설계:
 * - com.agent.span.Context 를 Reflection으로 호출 (직접 import 불가)
 * - 캡처된 Span은 Object 타입으로 보관
 *
 * 상용 APM 비교:
 * - Datadog  : ContextSnapshot (동일 이름)
 * - OTel     : ContextStorage
 * - Pinpoint : AsyncTraceContext
 */
public final class ContextSnapshot {

    /** 재진입 방지 — executor.execute() 내부에서 다시 execute()가 호출되는 경우 */
    static final ThreadLocal<Boolean> CAPTURING = new ThreadLocal<>();

    private ContextSnapshot() {}

    /**
     * 현재 스레드의 Span과 MDC를 캡처한다.
     *
     * @return [span (Object), mdc (Map<String,String>)] 배열, 또는 null (active span 없음)
     */
    public static Object[] capture() {
        if (Boolean.TRUE.equals(CAPTURING.get())) {
            return null; // 재진입 방지
        }
        CAPTURING.set(Boolean.TRUE);
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();

            Class<?> ctxClass = Class.forName("com.agent.span.Context", false, cl);
            Object span = ctxClass.getMethod("currentSpan").invoke(null);

            // NoopSpan 이면 캡처 불필요
            Boolean recording = (Boolean) span.getClass().getMethod("isRecording").invoke(span);
            if (!recording) return null;

            // MDC 캡처 (SLF4J)
            Map<String, String> mdcCopy = null;
            try {
                Class<?> mdcClass = Class.forName("org.slf4j.MDC", false, cl);
                @SuppressWarnings("unchecked")
                Map<String, String> mdc =
                        (Map<String, String>) mdcClass.getMethod("getCopyOfContextMap").invoke(null);
                if (mdc != null && !mdc.isEmpty()) {
                    mdcCopy = new HashMap<>(mdc);
                }
            } catch (Throwable ignored) {}

            return new Object[]{span, mdcCopy};

        } catch (Throwable t) {
            return null;
        } finally {
            CAPTURING.remove();
        }
    }

    /**
     * 캡처된 컨텍스트를 현재 스레드에 복원한다.
     *
     * @param snapshot capture()가 반환한 배열
     * @return 복원 취소용 Runnable — finally 블록에서 run() 호출 필수
     */
    public static Runnable restore(Object[] snapshot) {
        if (snapshot == null || snapshot[0] == null) return () -> {};
        try {
            Object span = snapshot[0];
            @SuppressWarnings("unchecked")
            Map<String, String> savedMdc = (Map<String, String>) snapshot[1];

            ClassLoader cl = Thread.currentThread().getContextClassLoader();

            // MDC 현재값 백업 후 저장된 값으로 교체
            Map<String, String> prevMdc = null;
            try {
                Class<?> mdcClass = Class.forName("org.slf4j.MDC", false, cl);
                @SuppressWarnings("unchecked")
                Map<String, String> cur =
                        (Map<String, String>) mdcClass.getMethod("getCopyOfContextMap").invoke(null);
                prevMdc = cur;
                if (savedMdc != null) {
                    mdcClass.getMethod("setContextMap", Map.class).invoke(null, savedMdc);
                }
            } catch (Throwable ignored) {}

            // Context.makeCurrent(span)
            Class<?> ctxClass = Class.forName("com.agent.span.Context", false, cl);
            Class<?> spanClass = Class.forName("com.agent.span.Span", false, cl);
            Object scope = ctxClass.getMethod("makeCurrent", spanClass).invoke(null, span);

            final Map<String, String> finalPrevMdc = prevMdc;
            return () -> {
                try {
                    scope.getClass().getMethod("close").invoke(scope);
                } catch (Throwable ignored) {}
                try {
                    Class<?> mdc2 = Class.forName(
                            "org.slf4j.MDC", false, Thread.currentThread().getContextClassLoader());
                    if (finalPrevMdc != null) {
                        mdc2.getMethod("setContextMap", Map.class).invoke(null, finalPrevMdc);
                    } else {
                        mdc2.getMethod("clear").invoke(null);
                    }
                } catch (Throwable ignored) {}
            };

        } catch (Throwable t) {
            return () -> {};
        }
    }
}

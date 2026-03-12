package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Spring Scheduled Task 계측.
 *
 * 대상: org.springframework.scheduling.support.ScheduledMethodRunnable.run()
 *
 * 선택 근거:
 * - ScheduledAnnotationBeanPostProcessor는 @Scheduled 메서드를 ScheduledMethodRunnable로 래핑한다.
 * - run()은 실제 @Scheduled 메서드 호출 직전의 단일 진입점이다.
 * - ThreadPoolTaskScheduler를 계측하면 스케줄러 내부 태스크까지 모두 캡처되어 과도하게 광범위하다.
 *
 * 중요한 설계 결정 — Root Span (부모 없음):
 * - @Scheduled 작업은 외부 요청과 무관하게 독립적으로 실행된다.
 * - 따라서 부모 span을 설정하지 않고 새로운 traceId를 가진 root span으로 생성한다.
 * - Context.currentSpan().isRecording() 체크를 의도적으로 생략한다.
 *   (일반 Advice와 달리, 활성 span이 없어도 새 trace를 시작해야 하기 때문)
 *
 * ScheduledMethodRunnable 내부 구조:
 *   private final Object target;   // 원본 빈 인스턴스
 *   private final Method method;   // @Scheduled 어노테이션이 붙은 메서드
 *
 * Span 명: "scheduled ClassName#methodName" (예: "scheduled OrderBatch#processExpired")
 *
 * 주의사항:
 * - @Scheduled 메서드가 예외를 던지면 스케줄러가 다음 실행을 취소하는 경우가 있으므로
 *   에러를 span에 기록하되 suppress=Throwable.class로 agent 자체의 예외는 모두 억제한다.
 * - 높은 주기(예: 100ms 간격)의 작업은 span 볼륨이 폭발적으로 증가할 수 있다.
 *   RemoteConfig로 scheduled 계측을 on/off할 수 있도록 ServiceDisable 체크를 포함한다.
 */
public final class ScheduledTaskAdvice {

    private static final String SERVICE_NAME = com.agent.config.AgentConfig.get().getServiceName();

    // ScheduledMethodRunnable 필드 캐시
    private static volatile Field TARGET_FIELD = null;
    private static volatile Field METHOD_FIELD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.This Object runnable) {

        // ServiceDisable 체크 — 원격 설정으로 계측 비활성화 가능
        com.agent.config.RemoteConfig remoteConfig = com.agent.config.RemoteConfigHolder.get();
        if (!remoteConfig.getServiceDisable().isEmpty()
                && remoteConfig.getServiceDisable().contains(SERVICE_NAME)) {
            return null;
        }

        String className = "unknown";
        String methodName = "unknown";

        try {
            // ScheduledMethodRunnable.target과 method 필드에서 클래스/메서드 정보 추출
            Object target = extractTarget(runnable);
            Method scheduledMethod = extractMethod(runnable);

            if (target != null) {
                className = target.getClass().getSimpleName();
                if (className.isEmpty()) {
                    // 익명 클래스나 람다는 getSimpleName()이 빈 문자열을 반환
                    className = target.getClass().getName();
                    int lastDot = className.lastIndexOf('.');
                    if (lastDot >= 0) className = className.substring(lastDot + 1);
                }
            }
            if (scheduledMethod != null) {
                methodName = scheduledMethod.getName();
            }
        } catch (Throwable ignored) {}

        // Root span — @Scheduled는 새로운 trace 시작점
        // 의도적으로 부모 span을 설정하지 않는다.
        String spanName = "scheduled " + className + "#" + methodName;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.scheduled");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL)
                // 부모를 명시적으로 설정하지 않음 → root span (새로운 traceId 생성)
                .setNoParent()
                .startSpan();

        span.setAttribute("scheduling.framework", "spring");
        span.setAttribute("scheduled.class", className);
        span.setAttribute("scheduled.method", methodName);

        Scope scope = span.makeCurrent();

        // MDC 주입 — 스케줄 실행 중 발생하는 로그에 traceId 포함
        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        AgentLogger.debug("[Scheduled] root span started: " + spanName);
        return new State(span, scope, prevTraceId, prevSpanId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Thrown Throwable error) {

        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[Scheduled] span error: " + error.getMessage());
        }
        state.scope.close();
        state.span.end();

        // MDC 복원 — 스케줄러 스레드 풀의 스레드를 재사용하므로 반드시 원복해야 한다
        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId == null) MDC.remove("spanId");
        else MDC.put("spanId", state.prevSpanId);
    }

    // --- reflection 헬퍼 ---

    private static Object extractTarget(Object runnable) {
        try {
            Field f = TARGET_FIELD;
            if (f == null) {
                f = findField(runnable.getClass(), "target");
                if (f != null) {
                    f.setAccessible(true);
                    TARGET_FIELD = f;
                }
            }
            return f != null ? f.get(runnable) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method extractMethod(Object runnable) {
        try {
            Field f = METHOD_FIELD;
            if (f == null) {
                f = findField(runnable.getClass(), "method");
                if (f != null) {
                    f.setAccessible(true);
                    METHOD_FIELD = f;
                }
            }
            return f != null ? (Method) f.get(runnable) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 클래스 계층을 올라가며 지정한 이름의 필드를 탐색한다.
     * getDeclaredField()는 상위 클래스 필드를 반환하지 않으므로 직접 순회가 필요하다.
     */
    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    public static final class State {
        public final Span span;
        public final Scope scope;
        public final String prevTraceId;
        public final String prevSpanId;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId) {
            this.span = span;
            this.scope = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId = prevSpanId;
        }
    }

    private ScheduledTaskAdvice() {}
}

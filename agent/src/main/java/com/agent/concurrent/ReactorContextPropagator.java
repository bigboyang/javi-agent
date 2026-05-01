package com.agent.concurrent;

import com.agent.logs.AgentLogger;

/**
 * Reactor Schedulers 훅을 통한 ThreadLocal span context 전파.
 *
 * WebFlux에서 publishOn/subscribeOn 같은 operator가 스레드를 전환할 때
 * Schedulers.onScheduleHook이 Runnable을 ContextPropagatingRunnable로 래핑하여
 * 부모 스레드의 span context를 자동 전파한다.
 *
 * 설치 시점: 첫 번째 WebFlux 요청 진입 시 지연 초기화 (Reactor가 앱 클래스로더에서 로드된 이후).
 * Reactor가 없는 환경(순수 Servlet)에서는 무시된다.
 *
 * 상용 APM 비교:
 * - Spring Sleuth / Micrometer Tracing : ReactorContextAccessor + onScheduleHook
 * - OTel Java agent                    : Hooks.enableAutomaticContextPropagation()
 */
public final class ReactorContextPropagator {

    /** Reactor Context에 span을 저장할 때 사용하는 키. */
    public static final String SPAN_KEY = "javi-apm.span";

    /** Reactor Context에 MDC 스냅샷을 저장할 때 사용하는 키. */
    public static final String MDC_KEY = "javi-apm.mdc";

    private static volatile boolean installed = false;
    private static final String HOOK_KEY = "javi-apm";

    private ReactorContextPropagator() {}

    /**
     * WebFlux 요청 진입 시 호출. 최초 1회만 훅을 설치한다.
     * 설치 실패(Reactor 없음 등)는 warn 로그 후 조용히 넘어간다.
     */
    public static void installIfNeeded() {
        if (installed) return;
        synchronized (ReactorContextPropagator.class) {
            if (installed) return;
            installed = true;
            try {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                Class<?> schedulersClass = Class.forName(
                        "reactor.core.scheduler.Schedulers", false, cl);
                java.util.function.Function<Runnable, Runnable> hook =
                        ContextPropagatingRunnable::wrap;
                schedulersClass
                        .getMethod("onScheduleHook", String.class, java.util.function.Function.class)
                        .invoke(null, HOOK_KEY, hook);
                AgentLogger.info("[Reactor] Schedulers.onScheduleHook 설치 완료 — "
                        + "publishOn/subscribeOn span context 전파 활성화");
            } catch (ClassNotFoundException e) {
                AgentLogger.debug("[Reactor] Reactor 미사용 — Schedulers 훅 스킵");
            } catch (Throwable t) {
                AgentLogger.warn("[Reactor] Schedulers 훅 설치 실패: " + t.getMessage());
            }
        }
    }
}

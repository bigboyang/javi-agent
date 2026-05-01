package com.agent.concurrent;

/**
 * 제출 시점(submit-time)의 span context를 캡처하여 실행 시점(run-time)에 복원하는 Runnable 래퍼.
 *
 * 상용 APM 동일 패턴:
 * - Datadog  : ContextTask
 * - OTel     : PropagatedRunnable
 * - Pinpoint : AsyncContextRunnable
 *
 * 사용 예:
 * <pre>
 *   executor.execute(ContextPropagatingRunnable.wrap(task));
 * </pre>
 */
public final class ContextPropagatingRunnable implements Runnable {

    private final Runnable delegate;
    private final Object[] snapshot; // [Span, Map<String,String> mdc]

    public ContextPropagatingRunnable(Runnable delegate, Object[] snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    @Override
    public void run() {
        Runnable restorer = ContextSnapshot.restore(snapshot);
        try {
            delegate.run();
        } finally {
            restorer.run();
        }
    }

    /**
     * 현재 활성 span이 있으면 래핑, 없으면 원본 반환.
     * 이미 래핑된 경우 이중 래핑 방지.
     *
     * ThreadLocal에 span이 없으면 Runnable의 Reactor Context를 폴백으로 시도한다.
     * publishOn(boundedElastic()) 경계에서 scope가 이미 닫혀있어도 propagation이 유지된다.
     */
    public static Runnable wrap(Runnable runnable) {
        if (runnable == null || runnable instanceof ContextPropagatingRunnable) {
            return runnable;
        }
        Object[] snapshot = ContextSnapshot.capture();
        if (snapshot == null) {
            // ThreadLocal이 비어 있으면 Reactor Context(CoreSubscriber.currentContext())에서 폴백
            snapshot = ContextSnapshot.captureFromReactorContext(runnable);
        }
        if (snapshot == null) return runnable;
        return new ContextPropagatingRunnable(runnable, snapshot);
    }
}

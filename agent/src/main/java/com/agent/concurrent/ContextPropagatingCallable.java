package com.agent.concurrent;

import java.util.concurrent.Callable;

/**
 * 제출 시점의 span context를 캡처하여 실행 시점에 복원하는 Callable 래퍼.
 * submit(Callable) 패턴에서 사용된다.
 */
public final class ContextPropagatingCallable<V> implements Callable<V> {

    private final Callable<V> delegate;
    private final Object[] snapshot;

    public ContextPropagatingCallable(Callable<V> delegate, Object[] snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    @Override
    public V call() throws Exception {
        Runnable restorer = ContextSnapshot.restore(snapshot);
        try {
            return delegate.call();
        } finally {
            restorer.run();
        }
    }

    /** 현재 활성 span이 있으면 래핑, 없으면 원본 반환. */
    public static <V> Callable<V> wrap(Callable<V> callable) {
        if (callable == null || callable instanceof ContextPropagatingCallable) {
            return callable;
        }
        Object[] snapshot = ContextSnapshot.capture();
        if (snapshot == null) return callable;
        return new ContextPropagatingCallable<>(callable, snapshot);
    }
}

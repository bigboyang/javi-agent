package com.agent.instrumentation;

import com.agent.concurrent.ContextPropagatingRunnable;
import com.agent.concurrent.ContextSnapshot;
import net.bytebuddy.asm.Advice;

/**
 * CompletableFuture 비동기 메서드 계측.
 *
 * 대상:
 *   - CompletableFuture.runAsync(Runnable)
 *   - CompletableFuture.runAsync(Runnable, Executor)
 *   - CompletableFuture.supplyAsync(Supplier)
 *   - CompletableFuture.supplyAsync(Supplier, Executor)
 *
 * 이유:
 *   CompletableFuture의 기본 executor는 ForkJoinPool.commonPool()이다.
 *   ForkJoinPool은 ThreadPoolExecutor가 아니므로 ExecutorServiceAdvice가 적용되지 않는다.
 *   여기서 직접 Runnable/Supplier를 context-aware 래퍼로 교체한다.
 */
public final class CompletableFutureAdvice {

    /** runAsync(Runnable) / runAsync(Runnable, Executor) 진입점 */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onRunAsync(
            @Advice.Argument(value = 0, readOnly = false) Runnable action) {

        if (action == null || action instanceof ContextPropagatingRunnable) return;
        Object[] snapshot = ContextSnapshot.capture();
        if (snapshot == null) return;
        action = new ContextPropagatingRunnable(action, snapshot);
    }

    private CompletableFutureAdvice() {}
}

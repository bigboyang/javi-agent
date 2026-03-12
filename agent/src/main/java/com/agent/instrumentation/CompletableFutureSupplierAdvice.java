package com.agent.instrumentation;

import com.agent.concurrent.ContextSnapshot;
import net.bytebuddy.asm.Advice;

import java.util.function.Supplier;

/**
 * CompletableFuture.supplyAsync(Supplier) 계측.
 * Supplier를 context-aware 람다로 교체하여 ForkJoinPool 쓰레드에서 span context를 복원한다.
 */
public final class CompletableFutureSupplierAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static <T> void onSupplyAsync(
            @Advice.Argument(value = 0, readOnly = false) Supplier<T> supplier) {

        if (supplier == null) return;
        Object[] snapshot = ContextSnapshot.capture();
        if (snapshot == null) return;

        final Supplier<T> original = supplier;
        final Object[] snap = snapshot;
        supplier = () -> {
            Runnable restorer = ContextSnapshot.restore(snap);
            try {
                return original.get();
            } finally {
                restorer.run();
            }
        };
    }

    private CompletableFutureSupplierAdvice() {}
}

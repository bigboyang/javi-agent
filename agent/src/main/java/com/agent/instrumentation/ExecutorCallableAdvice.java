package com.agent.instrumentation;

import com.agent.concurrent.ContextPropagatingCallable;
import com.agent.concurrent.ContextSnapshot;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.Callable;

/**
 * AbstractExecutorService.submit(Callable) 계측.
 * submit(Runnable) 은 내부적으로 execute()를 호출하므로 ExecutorServiceAdvice로 커버됨.
 */
public final class ExecutorCallableAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static <T> void onSubmit(
            @Advice.Argument(value = 0, readOnly = false) Callable<T> task) {

        if (task == null || task instanceof ContextPropagatingCallable) return;
        Object[] snapshot = ContextSnapshot.capture();
        if (snapshot == null) return;
        task = new ContextPropagatingCallable<>(task, snapshot);
    }

    private ExecutorCallableAdvice() {}
}

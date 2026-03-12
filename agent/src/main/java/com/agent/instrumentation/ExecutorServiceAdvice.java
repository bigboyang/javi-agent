package com.agent.instrumentation;

import com.agent.concurrent.ContextPropagatingRunnable;
import com.agent.concurrent.ContextSnapshot;
import net.bytebuddy.asm.Advice;

/**
 * ThreadPoolExecutor / AbstractExecutorService 계측.
 *
 * execute(Runnable)  → ContextPropagatingRunnable으로 교체
 * submit(Callable)   → ContextPropagatingCallable으로 교체
 *
 * @Advice.Argument(readOnly = false) 패턴:
 *   ByteBuddy가 파라미터를 로컬 변수로 복사한 뒤, Advice에서 재할당하면
 *   실제 메서드 호출 시 새 값이 전달된다.
 *
 * 이중 래핑 방지:
 *   instanceof 체크로 이미 래핑된 Runnable/Callable은 재래핑하지 않는다.
 */
public final class ExecutorServiceAdvice {

    /** execute(Runnable) 진입점 */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onExecute(
            @Advice.Argument(value = 0, readOnly = false) Runnable task) {

        if (task == null || task instanceof ContextPropagatingRunnable) return;
        Object[] snapshot = ContextSnapshot.capture();
        if (snapshot == null) return;
        task = new ContextPropagatingRunnable(task, snapshot);
    }

    private ExecutorServiceAdvice() {}
}

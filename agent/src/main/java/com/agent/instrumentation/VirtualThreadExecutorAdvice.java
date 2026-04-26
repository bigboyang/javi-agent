package com.agent.instrumentation;

import com.agent.concurrent.ContextPropagatingRunnable;
import com.agent.concurrent.ContextSnapshot;
import net.bytebuddy.asm.Advice;

/**
 * Java 21 Virtual Thread per-task executor 계측.
 *
 * 대상: java.util.concurrent.ThreadPerTaskExecutor (Java 21+)
 * - Executors.newVirtualThreadPerTaskExecutor() 반환 타입
 * - ThreadPoolExecutor를 상속하지 않아 기존 ExecutorServiceAdvice가 누락
 *
 * InheritableThreadLocal 변경과 중복이지만, 명시적 snapshot/restore로
 * 중첩 실행 및 엣지 케이스를 안전하게 처리한다.
 *
 * Java 11 소스 레벨 컴파일이므로 클래스명은 문자열로만 참조한다.
 */
public final class VirtualThreadExecutorAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onExecute(
            @Advice.Argument(value = 0, readOnly = false) Runnable task) {

        if (task == null || task instanceof ContextPropagatingRunnable) return;
        Object[] snapshot = ContextSnapshot.capture();
        if (snapshot == null) return;
        task = new ContextPropagatingRunnable(task, snapshot);
    }

    private VirtualThreadExecutorAdvice() {}
}

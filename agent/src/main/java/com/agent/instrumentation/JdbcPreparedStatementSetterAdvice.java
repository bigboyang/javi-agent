package com.agent.instrumentation;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for PreparedStatement.setXxx() / clearParameters() methods.
 *
 * 목적: execute() 시점에 바인딩 파라미터를 스팬 속성으로 기록하기 위해
 *       setXxx() 호출 시 파라미터 인덱스와 값을 JdbcParameterCapture에 저장한다.
 *
 * 처리 메서드:
 *  - setXxx(int paramIndex, T value) — 값을 저장
 *  - setNull(int paramIndex, int sqlType) — "NULL" 저장
 *  - clearParameters() — 보관된 파라미터 전체 삭제
 */
public final class JdbcPreparedStatementSetterAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This Object ps,
            @Advice.AllArguments Object[] args,
            @Advice.Origin("#m") String methodName) {
        if ("clearParameters".equals(methodName)) {
            JdbcParameterCapture.clear(ps);
            return;
        }
        if (args == null || args.length < 1 || !(args[0] instanceof Integer)) return;
        int idx = (Integer) args[0];
        if ("setNull".equals(methodName)) {
            JdbcParameterCapture.set(ps, idx, "NULL");
        } else if (args.length >= 2) {
            Object val = args[1];
            JdbcParameterCapture.set(ps, idx, val != null ? val.toString() : "NULL");
        }
    }

    private JdbcPreparedStatementSetterAdvice() {}
}

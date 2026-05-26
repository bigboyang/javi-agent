package com.agent.instrumentation;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * PreparedStatement 바인딩 파라미터를 스레드 로컬로 임시 보관한다.
 *
 * 흐름:
 *  1. JdbcPreparedStatementSetterAdvice — setXxx() 호출 시 파라미터 저장
 *  2. JdbcPreparedStatementAdvice        — execute() 진입 시 getAndClear()로 읽은 뒤 스팬에 기록
 *
 * 키: System.identityHashCode(PreparedStatement)
 * 값: paramIndex(1-based) → 문자열 표현 (TreeMap으로 순서 보장)
 */
public final class JdbcParameterCapture {

    private static final int MAX_VALUE_LEN = 100;
    private static final int MAX_PARAMS    = 20;

    private static final ThreadLocal<Map<Integer, TreeMap<Integer, String>>> PARAMS =
            ThreadLocal.withInitial(HashMap::new);

    /**
     * 파라미터를 저장한다. 동일 인덱스에 다시 set하면 덮어쓴다.
     *
     * @param ps         PreparedStatement 인스턴스
     * @param paramIndex JDBC 파라미터 인덱스 (1-based)
     * @param value      문자열화된 파라미터 값 (null → "NULL")
     */
    public static void set(Object ps, int paramIndex, String value) {
        try {
            int id = System.identityHashCode(ps);
            TreeMap<Integer, String> map = PARAMS.get().computeIfAbsent(id, k -> new TreeMap<>());
            if (map.size() >= MAX_PARAMS) return;
            if (value != null && value.length() > MAX_VALUE_LEN) {
                value = value.substring(0, MAX_VALUE_LEN) + "…";
            }
            map.put(paramIndex, value != null ? value : "NULL");
        } catch (Throwable ignored) {}
    }

    /**
     * 지정 PreparedStatement의 파라미터를 읽고 ThreadLocal에서 제거한다.
     *
     * @return "$1=val1, $2=val2, …" 형식의 문자열, 파라미터 없으면 null
     */
    public static String getAndClear(Object ps) {
        try {
            int id = System.identityHashCode(ps);
            TreeMap<Integer, String> params = PARAMS.get().remove(id);
            if (params == null || params.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, String> e : params.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append('$').append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * PreparedStatement의 파라미터를 제거한다 (clearParameters() 호출 시).
     */
    public static void clear(Object ps) {
        try {
            PARAMS.get().remove(System.identityHashCode(ps));
        } catch (Throwable ignored) {}
    }

    private JdbcParameterCapture() {}
}

package com.agent.instrumentation;

import com.agent.common.utils.SqlSanitizer;
import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ByteBuddy advice for JDBC PreparedStatement execute methods (no SQL argument).
 *
 * 대상: PreparedStatement.execute(), executeQuery(), executeUpdate()
 *
 * db.system: JdbcStatementAdvice 공유 헬퍼를 통해 실제 DB 벤더 감지.
 * db.name, net.peer.name, net.peer.port: JDBC URL 파싱으로 추출.
 * db.user: deprecated/removed in OTel 1.24+ — not emitted.
 *
 * 성능 최적화:
 *  - getDeclaredFields() 전체 스캔을 제거하고 Field를 클래스별로 캐싱
 *  - getConnection 메서드도 클래스별로 캐싱 (JdbcStatementAdvice 공유 캐시 활용)
 *  - DB 메트릭 기록을 JdbcStatementAdvice 공유 메서드로 위임하여 캐시 공유
 */
public final class JdbcPreparedStatementAdvice {

    /**
     * PreparedStatement 클래스별 sql/originalSql 필드 캐시.
     *
     * <p>값이 ABSENT_SENTINEL 이면 해당 클래스에 sql 필드가 없다는 뜻이므로
     * getDeclaredFields() 재스캔을 방지한다.
     */
    public static final ConcurrentHashMap<Class<?>, Field> SQL_FIELD_CACHE = new ConcurrentHashMap<>(4);
    public static final Field ABSENT_SENTINEL;
    static {
        try {
            ABSENT_SENTINEL = JdbcPreparedStatementAdvice.class.getDeclaredField("ABSENT_SENTINEL");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.This Object preparedStatement) {
        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.jdbc");

        // SQL 추출: 클래스별 캐싱된 Field 우선, 없으면 toString() fallback
        String sql = extractSql(preparedStatement);

        String maskedSql = SqlSanitizer.sanitize(sql);
        String spanName = maskedSql.length() > 60
                ? maskedSql.substring(0, 60) + "..." : maskedSql;

        Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("db.query.text", maskedSql);

        // DB 메타데이터 추출 — JdbcStatementAdvice의 캐시 공유
        String dbSystem = "other_sql";
        if (preparedStatement != null) {
            try {
                Class<?> psClass = preparedStatement.getClass();
                Method mGetConn = JdbcStatementAdvice.GET_CONNECTION_CACHE.get(psClass);
                if (mGetConn == null) {
                    mGetConn = psClass.getMethod("getConnection");
                    JdbcStatementAdvice.GET_CONNECTION_CACHE.put(psClass, mGetConn);
                }
                Object conn = mGetConn.invoke(preparedStatement);
                if (conn != null) dbSystem = JdbcStatementAdvice.extractConnMetadata(conn, span);
            } catch (Throwable ignored) {}
        }
        span.setAttribute("db.system", dbSystem);

        String dbOperation = JdbcStatementAdvice.extractDbOperation(sql);

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[JDBC-PS] span started: " + maskedSql);
        return new State(span, scope, dbSystem, dbOperation, System.nanoTime());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[JDBC-PS] span error: " + error.getMessage());
        }
        // Exemplar 캡처를 위해 scope.close() 전에 메트릭 기록
        // JdbcStatementAdvice 공유 메서드 — 캐시 공유로 이중 등록 없음
        JdbcStatementAdvice.recordDbMetrics(state.dbSystem, state.dbOperation, state.startNano, error);
        state.scope.close();
        state.span.end();
    }

    /**
     * PreparedStatement 구현체에서 SQL 문자열을 추출한다.
     *
     * <p>클래스별로 Field를 캐싱하여 getDeclaredFields() 전체 스캔을
     * 최초 1회로 제한한다.
     */
    public static String extractSql(Object preparedStatement) {
        if (preparedStatement == null) return "PreparedStatement";

        Class<?> psClass = preparedStatement.getClass();
        Field sqlField = SQL_FIELD_CACHE.get(psClass);

        if (sqlField == null) {
            // 최초 진입: 클래스 계층에서 sql/originalSql 필드 검색
            Field found = null;
            Class<?> clazz = psClass;
            outer:
            while (clazz != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if ("sql".equals(f.getName()) || "originalSql".equals(f.getName())) {
                        f.setAccessible(true);
                        found = f;
                        break outer;
                    }
                }
                clazz = clazz.getSuperclass();
            }
            sqlField = (found != null) ? found : ABSENT_SENTINEL;
            SQL_FIELD_CACHE.put(psClass, sqlField);
        }

        if (sqlField != ABSENT_SENTINEL) {
            try {
                Object val = sqlField.get(preparedStatement);
                if (val instanceof String) return (String) val;
            } catch (Throwable ignored) {}
        }

        // toString() fallback
        String str = preparedStatement.toString();
        return (str != null) ? str : "PreparedStatement";
    }

    public static final class State {
        public final Span   span;
        public final Scope  scope;
        public final String dbSystem;
        public final String dbOperation;
        public final long   startNano;

        public State(Span span, Scope scope, String dbSystem, String dbOperation, long startNano) {
            this.span        = span;
            this.scope       = scope;
            this.dbSystem    = dbSystem;
            this.dbOperation = dbOperation;
            this.startNano   = startNano;
        }
    }

    private JdbcPreparedStatementAdvice() {}
}

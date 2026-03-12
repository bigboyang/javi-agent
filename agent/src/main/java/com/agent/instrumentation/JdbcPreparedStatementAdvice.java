package com.agent.instrumentation;

import com.agent.common.utils.SqlSanitizer;
import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for JDBC PreparedStatement execute methods (no SQL argument).
 *
 * 대상: PreparedStatement.execute(), executeQuery(), executeUpdate()
 *
 * db.system: JdbcStatementAdvice 공유 헬퍼를 통해 실제 DB 벤더 감지.
 * db.name, net.peer.name, net.peer.port: JDBC URL 파싱으로 추출.
 * db.user: Connection.getMetaData().getUserName().
 */
public final class JdbcPreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.This Object preparedStatement) {
        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.jdbc");

        // SQL 추출: 필드 직접 접근 → toString() fallback
        String sql = "PreparedStatement";
        if (preparedStatement != null) {
            try {
                java.lang.reflect.Field[] fields = preparedStatement.getClass().getDeclaredFields();
                for (java.lang.reflect.Field f : fields) {
                    if ("sql".equals(f.getName()) || "originalSql".equals(f.getName())) {
                        f.setAccessible(true);
                        Object val = f.get(preparedStatement);
                        if (val instanceof String) { sql = (String) val; break; }
                    }
                }
            } catch (Throwable ignored) {}
            if ("PreparedStatement".equals(sql)) {
                String str = preparedStatement.toString();
                if (str != null) sql = str;
            }
        }

        String maskedSql = SqlSanitizer.sanitize(sql);
        String spanName = maskedSql.length() > 60
                ? maskedSql.substring(0, 60) + "..." : maskedSql;

        Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
        span.setAttribute("db.query.text", maskedSql);

        // DB 메타데이터 추출
        String dbSystem = "other_sql";
        if (preparedStatement != null) {
            try {
                Object conn = preparedStatement.getClass().getMethod("getConnection").invoke(preparedStatement);
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
        state.scope.close();
        state.span.end();
        recordDbMetrics(state, error);
    }

    private static void recordDbMetrics(State state, Throwable error) {
        try {
            long durationMs = (System.nanoTime() - state.startNano) / 1_000_000L;
            java.util.Map<String, String> tags = new java.util.HashMap<>(4);
            tags.put("db.system", state.dbSystem);
            tags.put("db.operation", state.dbOperation);

            com.agent.metric.MetricRegistry reg = com.agent.metric.MetricRegistry.get();
            reg.counter("db.client.operation.count", tags).increment();
            reg.histogram("db.client.operation.duration", tags).record(durationMs);
            if (error != null) {
                reg.counter("db.client.operation.error.count", tags).increment();
            }
        } catch (Throwable ignored) {}
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

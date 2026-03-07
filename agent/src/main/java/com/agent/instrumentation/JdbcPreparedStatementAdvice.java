package com.agent.instrumentation;

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
 * SQL 추출: preparedStatement.toString()을 통해 드라이버가 제공하는 SQL 정보를 활용.
 *          H2, MySQL, PostgreSQL 등 주요 드라이버는 toString()에 SQL을 포함한다.
 */
public final class JdbcPreparedStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.This Object preparedStatement) {
        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.jdbc");

        // SQL 인라인 추출 (helper 메서드 호출 대신)
        String sql = "PreparedStatement";
        if (preparedStatement != null) {
            try {
                java.lang.reflect.Field[] fields = preparedStatement.getClass().getDeclaredFields();
                for (java.lang.reflect.Field f : fields) {
                    if ("sql".equals(f.getName()) || "originalSql".equals(f.getName())) {
                        f.setAccessible(true);
                        Object val = f.get(preparedStatement);
                        if (val instanceof String) {
                            sql = (String) val;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if ("PreparedStatement".equals(sql)) {
                String str = preparedStatement.toString();
                if (str != null) sql = str;
            }
        }
        String spanName = sql.length() > 60 ? sql.substring(0, 60) + "..." : sql;
        

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "sql");
        span.setAttribute("db.statement", sql);

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[JDBC-PS] span started: " + sql);
        return new State(span, scope);
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
    }

    static final class State {
        final Span span;
        final Scope scope;

        State(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }

    private JdbcPreparedStatementAdvice() {}
}

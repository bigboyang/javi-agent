package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for JDBC Statement execute methods (with explicit SQL argument).
 *
 * 대상: Statement.execute(String), executeQuery(String), executeUpdate(String)
 * 스팬 이름: SQL 문의 앞 60자 (OTel DB 시맨틱 컨벤션)
 */
public final class JdbcStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) String sql) {
        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.jdbc");

        // span name 인라인 계산
        String spanName;
        if (sql == null || sql.isEmpty()) {
            spanName = "DB Query";
        } else {
            String trimmed = sql.trim();
            spanName = trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
        }

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "sql");
        if (sql != null) {
            span.setAttribute("db.statement", sql);
        }

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[JDBC] span started: " + sql);
        return new State(span, scope);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[JDBC] span error: " + error.getMessage());
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

    private JdbcStatementAdvice() {}
}

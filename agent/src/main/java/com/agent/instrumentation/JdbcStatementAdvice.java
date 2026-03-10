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
 * ByteBuddy advice for JDBC Statement execute methods (with explicit SQL argument).
 *
 * 대상: Statement.execute(String), executeQuery(String), executeUpdate(String)
 * 스팬 이름: SQL 문의 앞 60자 (OTel DB 시맨틱 컨벤션)
 */
public final class JdbcStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) String sql) {
        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.jdbc");

        // 민감한 리터럴 값 마스킹
        String maskedSql = SqlSanitizer.sanitize(sql);

        // span name 인라인 계산
        String spanName;
        if (maskedSql == null || maskedSql.isEmpty()) {
            spanName = "DB Query";
        } else {
            String trimmed = maskedSql.trim();
            spanName = trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
        }

        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "sql");
        if (maskedSql != null) {
            span.setAttribute("db.query.text", maskedSql);
        }

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[JDBC] span started: " + maskedSql);
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

    public static final class State {
        public final Span span;
        public final Scope scope;

        public State(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }

    private JdbcStatementAdvice() {}
}

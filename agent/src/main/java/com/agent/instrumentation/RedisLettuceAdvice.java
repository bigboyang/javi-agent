package com.agent.instrumentation;

import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * Redis Lettuce 계측.
 * io.lettuce.core.protocol.CommandHandler.dispatch()를 가로채어 Redis 명령어를 추적합니다.
 */
public final class RedisLettuceAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) Object command) {
        if (command == null) return null;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.redis.lettuce");
        
        // Command에서 이름 추출 (Reflection)
        String commandName = "REDIS";
        try {
            commandName = command.getClass().getMethod("getType").invoke(command).toString();
        } catch (Throwable ignored) {}

        Span span = tracer.spanBuilder("REDIS " + commandName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "redis");
        span.setAttribute("db.operation", commandName);

        return new State(span, span.makeCurrent());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(com.agent.span.SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();
    }

    public static final class State {
        public final Span span;
        public final com.agent.span.Scope scope;
        public State(Span span, com.agent.span.Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }
}

package com.agent.instrumentation;

import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for controller methods.
 */
public final class ControllerMethodAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Origin String origin) {
        System.out.println("[agent] enter " + origin);
        Tracer tracer = AgentRuntime.tracer();
        Span span = tracer.spanBuilder("controller:" + origin).startSpan();
        Scope scope = span.makeCurrent();
        return new State(span, scope);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) {
            return;
        }
        System.out.println("[agent] exit " + state.span.getName()
                + (error == null ? "" : " error=" + error.getClass().getName()));
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(com.agent.span.SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();
    }

    static final class State {
        private final Span span;
        private final Scope scope;

        State(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }
    }

    private ControllerMethodAdvice() {}
}

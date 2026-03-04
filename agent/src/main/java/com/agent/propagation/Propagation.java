package com.agent.propagation;

import com.agent.common.utils.time.AnchoredClock;
import com.agent.common.utils.time.Clock;
import com.agent.span.Context;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;

/**
 * Helper for inject/extract using the global propagator.
 */
public final class Propagation {
    private Propagation() {}

    public static <C> void injectCurrent(C carrier, TextMapSetter<C> setter) {
        SpanContext context = Context.currentSpan().getContext();
        ContextPropagators.getTextMapPropagator().inject(context, carrier, setter);
    }

    public static <C> SpanContext extract(C carrier, TextMapGetter<C> getter) {
        return ContextPropagators.getTextMapPropagator().extract(carrier, getter);
    }

    public static <C> Scope extractAndMakeCurrent(C carrier, TextMapGetter<C> getter) {
        SpanContext context = extract(carrier, getter);
        AnchoredClock anchoredClock = AnchoredClock.create(Clock.system());
        return Span.wrap(context, anchoredClock).makeCurrent();
    }
}

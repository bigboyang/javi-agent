package com.agent.propagation;

import com.agent.span.SpanContext;
import java.util.Collection;
import java.util.Collections;

/** No-op propagator. */
public final class NoopTextMapPropagator implements TextMapPropagator {
    private static final NoopTextMapPropagator INSTANCE = new NoopTextMapPropagator();

    private NoopTextMapPropagator() {}

    public static NoopTextMapPropagator getInstance() {
        return INSTANCE;
    }

    @Override
    public <C> void inject(SpanContext context, C carrier, TextMapSetter<C> setter) {
        // no-op
    }

    @Override
    public <C> SpanContext extract(C carrier, TextMapGetter<C> getter) {
        return SpanContext.invalid();
    }

    @Override
    public Collection<String> fields() {
        return Collections.emptyList();
    }
}

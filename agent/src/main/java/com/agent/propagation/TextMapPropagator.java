package com.agent.propagation;

import com.agent.span.SpanContext;
import java.util.Collection;

/**
 * Propagator interface for injecting/extracting trace context.
 */
public interface TextMapPropagator {
    <C> void inject(SpanContext context, C carrier, TextMapSetter<C> setter);

    <C> SpanContext extract(C carrier, TextMapGetter<C> getter);

    Collection<String> fields();
}

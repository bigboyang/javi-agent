package com.agent.propagation;

import com.agent.span.SpanContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Delegates inject/extract to multiple propagators. */
public final class MultiTextMapPropagator implements TextMapPropagator {
    private final List<TextMapPropagator> propagators;
    private final Collection<String> fields;

    private MultiTextMapPropagator(List<TextMapPropagator> propagators) {
        this.propagators = propagators;
        this.fields = buildFields(propagators);
    }

    public static TextMapPropagator create(List<TextMapPropagator> propagators) {
        if (propagators == null || propagators.isEmpty()) {
            return NoopTextMapPropagator.getInstance();
        }
        if (propagators.size() == 1) {
            return propagators.get(0);
        }
        return new MultiTextMapPropagator(new ArrayList<>(propagators));
    }

    @Override
    public <C> void inject(SpanContext context, C carrier, TextMapSetter<C> setter) {
        for (TextMapPropagator propagator : propagators) {
            propagator.inject(context, carrier, setter);
        }
    }

    @Override
    public <C> SpanContext extract(C carrier, TextMapGetter<C> getter) {
        SpanContext result = SpanContext.invalid();
        for (TextMapPropagator propagator : propagators) {
            SpanContext extracted = propagator.extract(carrier, getter);
            if (extracted != null && extracted.isValid()) {
                result = extracted;
            }
        }
        return result;
    }

    @Override
    public Collection<String> fields() {
        return fields;
    }

    private static Collection<String> buildFields(List<TextMapPropagator> propagators) {
        if (propagators == null || propagators.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> merged = new LinkedHashSet<>();
        for (TextMapPropagator propagator : propagators) {
            merged.addAll(propagator.fields());
        }
        return Collections.unmodifiableSet(merged);
    }
}

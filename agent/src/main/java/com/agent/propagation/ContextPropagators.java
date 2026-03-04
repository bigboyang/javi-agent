package com.agent.propagation;

/**
 * Holds the global TextMapPropagator configuration.
 */
public final class ContextPropagators {
    private static volatile TextMapPropagator propagator = TraceContextPropagator.getInstance();

    private ContextPropagators() {}

    public static TextMapPropagator getTextMapPropagator() {
        return propagator;
    }

    public static void setTextMapPropagator(TextMapPropagator propagator) {
        if (propagator != null) {
            ContextPropagators.propagator = propagator;
        }
    }
}

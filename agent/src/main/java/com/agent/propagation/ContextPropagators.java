package com.agent.propagation;

/**
 * 글로벌 전파기(TextMapPropagator, BaggagePropagator) 설정을 보관한다.
 */
public final class ContextPropagators {
    private static volatile TextMapPropagator propagator = TraceContextPropagator.getInstance();
    private static volatile BaggagePropagator baggagePropagator = new W3CBaggagePropagator();

    private ContextPropagators() {}

    /** TraceContext 전파기 획득 */
    public static TextMapPropagator getTextMapPropagator() {
        return propagator;
    }

    /** TraceContext 전파기 설정 */
    public static void setTextMapPropagator(TextMapPropagator propagator) {
        if (propagator != null) {
            ContextPropagators.propagator = propagator;
        }
    }

    /** Baggage 전파기 획득 */
    public static BaggagePropagator getBaggagePropagator() {
        return baggagePropagator;
    }

    /** Baggage 전파기 설정 */
    public static void setBaggagePropagator(BaggagePropagator baggagePropagator) {
        if (baggagePropagator != null) {
            ContextPropagators.baggagePropagator = baggagePropagator;
        }
    }
}

package com.agent.propagation;

import com.agent.span.SpanContext;
import com.agent.span.SpanId;
import com.agent.span.TraceFlags;
import com.agent.span.TraceState;
import com.agent.trace.TraceId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * W3C traceparent 기반 전파를 지원하는 간단한 프로파게이터.
 */
public final class TraceContextPropagator implements TextMapPropagator {
    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final String VERSION = "00";
    private static final Collection<String> FIELDS =
            Collections.unmodifiableList(Arrays.asList(TRACEPARENT, TRACESTATE));
    private static final TraceContextPropagator INSTANCE = new TraceContextPropagator();

    private TraceContextPropagator() {}

    public static TraceContextPropagator getInstance() {
        return INSTANCE;
    }

    @Override
    public <C> void inject(SpanContext context, C carrier, TextMapSetter<C> setter) {
        if (context == null || setter == null || carrier == null) {
            return;
        }
        String flags = toHexByte(context.getTraceFlags().asByte());
        String value = VERSION + "-" + context.getTraceId() + "-" + context.getSpanId() + "-" + flags;
        setter.set(carrier, TRACEPARENT, value);
        if (context.getTraceState() != null && !context.getTraceState().getValue().isEmpty()) {
            setter.set(carrier, TRACESTATE, context.getTraceState().getValue());
        }
    }

    @Override
    public <C> SpanContext extract(C carrier, TextMapGetter<C> getter) {
        if (carrier == null || getter == null) {
            return SpanContext.invalid();
        }
        String value = getter.get(carrier, TRACEPARENT);
        if (value == null) {
            return SpanContext.invalid();
        }
        String[] parts = value.split("-");
        if (parts.length != 4) {
            return SpanContext.invalid();
        }
        String traceId = parts[1];
        String spanId = parts[2];
        String flagsHex = parts[3];
        if (!TraceId.isValid(traceId) || !SpanId.isValid(spanId) || flagsHex.length() != 2) {
            return SpanContext.invalid();
        }
        TraceFlags flags = fromHexByte(flagsHex);
        TraceState traceState = TraceState.getDefault();
        String state = getter.get(carrier, TRACESTATE);
        if (state != null && !state.isEmpty()) {
            traceState = TraceState.fromHeader(state);
        }
        return SpanContext.create(
                traceId,
                spanId,
                SpanId.getInvalid(),
                flags.isSampled(),
                flags,
                traceState);
    }

    @Override
    public Collection<String> fields() {
        return FIELDS;
    }

    // Backwards-compatible static helpers.
    public static <C> void injectStatic(SpanContext context, C carrier, TextMapSetter<C> setter) {
        INSTANCE.inject(context, carrier, setter);
    }

    public static <C> SpanContext extractStatic(C carrier, TextMapGetter<C> getter) {
        return INSTANCE.extract(carrier, getter);
    }

    /** Extracts SpanContext directly from header strings, avoiding a carrier Map allocation. */
    public static SpanContext extractStatic(String traceparent, String tracestate) {
        if (traceparent == null) return SpanContext.invalid();
        String[] parts = traceparent.split("-");
        if (parts.length != 4) return SpanContext.invalid();
        String traceId  = parts[1];
        String spanId   = parts[2];
        String flagsHex = parts[3];
        if (!TraceId.isValid(traceId) || !SpanId.isValid(spanId) || flagsHex.length() != 2) {
            return SpanContext.invalid();
        }
        TraceFlags flags = fromHexByte(flagsHex);
        TraceState traceState = TraceState.getDefault();
        if (tracestate != null && !tracestate.isEmpty()) {
            traceState = TraceState.fromHeader(tracestate);
        }
        return SpanContext.create(traceId, spanId, SpanId.getInvalid(), flags.isSampled(), flags, traceState);
    }

    private static String toHexByte(byte value) {
        int v = value & 0xFF;
        String hex = Integer.toHexString(v);
        return hex.length() == 1 ? "0" + hex : hex;
    }

    private static TraceFlags fromHexByte(String hex) {
        int v = Integer.parseInt(hex, 16);
        return (v & 0x01) == 0x01 ? TraceFlags.sampled() : TraceFlags.getDefault();
    }
}

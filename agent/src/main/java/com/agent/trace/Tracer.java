package com.agent.trace;

import com.agent.span.SpanBuilder;

public interface Tracer {
    SpanBuilder spanBuilder(String name);
}

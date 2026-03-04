package com.agent.instrumentation;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.trace.SdkTracerProvider;
import com.agent.trace.Tracer;
import com.agent.trace.exporter.LoggingSpanExporter;

/**
 * Holds global tracer provider for agent instrumentation.
 */
public final class AgentRuntime {
    private static final SdkTracerProvider PROVIDER = new SdkTracerProvider(IdGenerator.random());
    private static final Tracer TRACER;

    static {
        PROVIDER.addSimpleSpanProcessor(new LoggingSpanExporter());
        TRACER = PROVIDER.getTracer("agent-auto");
    }

    private AgentRuntime() {}

    public static Tracer tracer() {
        return TRACER;
    }
}

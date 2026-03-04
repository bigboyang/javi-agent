package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import java.util.ArrayList;
import java.util.List;

/** Delegates span events to multiple processors in order. */
public final class CompositeSpanProcessor implements SpanProcessor {
    private final List<SpanProcessor> processors;

    private CompositeSpanProcessor(List<SpanProcessor> processors) {
        this.processors = processors;
    }

    public static SpanProcessor create(List<SpanProcessor> processors) {
        if (processors == null || processors.isEmpty()) {
            return NoopSpanProcessor.getInstance();
        }
        if (processors.size() == 1) {
            return processors.get(0);
        }
        return new CompositeSpanProcessor(new ArrayList<>(processors));
    }

    @Override
    public void onStart(Span span) {
        for (SpanProcessor processor : processors) {
            processor.onStart(span);
        }
    }

    @Override
    public void onEnd(Span span) {
        for (SpanProcessor processor : processors) {
            processor.onEnd(span);
        }
    }

    @Override
    public CompletableResultCode forceFlush() {
        List<CompletableResultCode> results = new ArrayList<>(processors.size());
        for (SpanProcessor processor : processors) {
            results.add(processor.forceFlush());
        }
        return CompletableResultCode.ofAll(results);
    }

    @Override
    public CompletableResultCode shutdown() {
        List<CompletableResultCode> results = new ArrayList<>(processors.size());
        for (SpanProcessor processor : processors) {
            results.add(processor.shutdown());
        }
        return CompletableResultCode.ofAll(results);
    }
}

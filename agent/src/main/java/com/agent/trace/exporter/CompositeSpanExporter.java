package com.agent.trace.exporter;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Exports spans to multiple exporters in order. */
public final class CompositeSpanExporter implements SpanExporter {
    private final List<SpanExporter> exporters;

    private CompositeSpanExporter(List<SpanExporter> exporters) {
        this.exporters = exporters;
    }

    public static SpanExporter create(List<SpanExporter> exporters) {
        if (exporters == null || exporters.isEmpty()) {
            return NoopSpanExporter.getInstance();
        }
        if (exporters.size() == 1) {
            return exporters.get(0);
        }
        return new CompositeSpanExporter(new ArrayList<>(exporters));
    }

    @Override
    public CompletableResultCode export(Collection<Span> spans) {
        List<CompletableResultCode> results = new ArrayList<>(exporters.size());
        for (SpanExporter exporter : exporters) {
            if (exporter == null) {
                results.add(CompletableResultCode.ofFailure());
                continue;
            }
            results.add(exporter.export(spans));
        }
        return CompletableResultCode.ofAll(results);
    }

    @Override
    public CompletableResultCode flush() {
        List<CompletableResultCode> results = new ArrayList<>(exporters.size());
        for (SpanExporter exporter : exporters) {
            results.add(exporter == null ? CompletableResultCode.ofFailure() : exporter.flush());
        }
        return CompletableResultCode.ofAll(results);
    }

    @Override
    public CompletableResultCode shutdown() {
        List<CompletableResultCode> results = new ArrayList<>(exporters.size());
        for (SpanExporter exporter : exporters) {
            results.add(exporter == null ? CompletableResultCode.ofFailure() : exporter.shutdown());
        }
        return CompletableResultCode.ofAll(results);
    }
}

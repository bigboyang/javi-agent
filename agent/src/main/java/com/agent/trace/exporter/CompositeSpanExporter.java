package com.agent.trace.exporter;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

/** Exports spans to multiple exporters concurrently. */
public final class CompositeSpanExporter implements SpanExporter {

    private static final ExecutorService DISPATCH_EXECUTOR = new ThreadPoolExecutor(
            2, Math.max(4, Runtime.getRuntime().availableProcessors()),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            r -> { Thread t = new Thread(r, "javi-composite-export"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

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
            CompletableResultCode result = new CompletableResultCode();
            results.add(result);
            CompletableFuture.supplyAsync(() -> exporter.export(spans), DISPATCH_EXECUTOR)
                    .whenComplete((code, ex) -> {
                        if (ex != null) { result.fail(); return; }
                        code.toCompletableFuture().whenComplete((v, t) -> {
                            if (t != null || !code.isSuccess()) result.fail();
                            else result.succeed();
                        });
                    });
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

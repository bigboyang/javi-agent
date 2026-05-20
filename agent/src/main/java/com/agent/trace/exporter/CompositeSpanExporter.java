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

    // exportAsync를 올바르게 오버라이드: 각 exporter의 exportAsync 결과를 합산해
    // BatchSpanProcessor가 isSuccess()를 완료된 future에서 확인할 수 있도록 한다.
    // 기본 구현(default)은 export()를 동기로 호출한 후 completedFuture로 감싸기 때문에
    // export()가 반환하는 미완성 CompletableResultCode를 즉시 isSuccess() 검사해 항상 false를 반환하는 버그가 있다.
    @Override
    public CompletableFuture<CompletableResultCode> exportAsync(Collection<Span> spans) {
        List<CompletableFuture<CompletableResultCode>> futures = new ArrayList<>(exporters.size());
        for (SpanExporter exporter : exporters) {
            if (exporter == null) {
                futures.add(CompletableFuture.completedFuture(CompletableResultCode.ofFailure()));
                continue;
            }
            futures.add(exporter.exportAsync(spans));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    for (CompletableFuture<CompletableResultCode> f : futures) {
                        CompletableResultCode r = f.getNow(null);
                        if (r == null || !r.isSuccess()) return CompletableResultCode.ofFailure();
                    }
                    return CompletableResultCode.ofSuccess();
                });
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

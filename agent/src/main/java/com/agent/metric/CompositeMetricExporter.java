package com.agent.metric;

import com.agent.common.DataExporter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

/**
 * 여러 MetricExporter에 동시에 데이터를 전달하는 합성 익스포터.
 */
public final class CompositeMetricExporter implements DataExporter<MetricData> {

    private static final ExecutorService DISPATCH_EXECUTOR = new ThreadPoolExecutor(
            2, Math.max(4, Runtime.getRuntime().availableProcessors()),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            r -> { Thread t = new Thread(r, "javi-composite-metric-export"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final List<DataExporter<MetricData>> exporters;

    private CompositeMetricExporter(List<DataExporter<MetricData>> exporters) {
        this.exporters = exporters;
    }

    @SafeVarargs
    public static CompositeMetricExporter of(DataExporter<MetricData>... exporters) {
        return new CompositeMetricExporter(Arrays.asList(exporters));
    }

    @Override
    public CompletableFuture<Void> export(Collection<MetricData> items) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(exporters.size());
        for (DataExporter<MetricData> exporter : exporters) {
            futures.add(
                CompletableFuture.supplyAsync(() -> exporter.export(items), DISPATCH_EXECUTOR)
                    .thenCompose(f -> f)
                    .exceptionally(e -> null)
            );
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        List<CompletableFuture<Void>> futures = new ArrayList<>(exporters.size());
        for (DataExporter<MetricData> exporter : exporters) {
            futures.add(
                CompletableFuture.supplyAsync(() -> exporter.shutdown(), DISPATCH_EXECUTOR)
                    .thenCompose(f -> f)
                    .exceptionally(e -> null)
            );
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}

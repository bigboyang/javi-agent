package com.agent.metric;

import com.agent.common.DataExporter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 여러 MetricExporter에 순서대로 데이터를 전달하는 합성 익스포터.
 * FileMetricExporter + OtlpGrpcMetricExporter 조합에 활용한다.
 */
public final class CompositeMetricExporter implements DataExporter<MetricData> {

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
        for (DataExporter<MetricData> exporter : exporters) {
            try {
                exporter.export(items);
            } catch (Throwable ignored) {}
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        for (DataExporter<MetricData> exporter : exporters) {
            try {
                exporter.shutdown();
            } catch (Throwable ignored) {}
        }
        return CompletableFuture.completedFuture(null);
    }
}

package com.agent.metric;

import com.agent.common.DataExporter;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * 수집된 메트릭 데이터를 파일(또는 나중에 OTLP)로 내보내는 구현체.
 */
public class FileMetricExporter implements DataExporter<MetricData> {

    @Override
    public CompletableFuture<Void> export(Collection<MetricData> metrics) {
        for (MetricData metric : metrics) {
            for (MetricData.Point point : metric.getPoints()) {
                MetricLogger.log("[MetricExport] " + metric.getName() + 
                                 " = " + point.getValue() + 
                                 " type=" + metric.getType() + 
                                 " attrs=" + point.getAttributes());
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.completedFuture(null);
    }
}

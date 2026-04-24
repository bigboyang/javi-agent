package com.agent.metric;

import com.agent.common.DataExporter;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class FileMetricExporter implements DataExporter<MetricData> {

    @Override
    public CompletableFuture<Void> export(Collection<MetricData> metrics) {
        for (MetricData metric : metrics) {
            if (metric == null) continue;
            MetricData.MetricType type = metric.getType() != null ? metric.getType() : MetricData.MetricType.GAUGE;

            if (type == MetricData.MetricType.HISTOGRAM) {
                exportHistogram(metric);
            } else {
                for (MetricData.Point point : metric.getPoints()) {
                    if (point == null) continue;
                    MetricLogger.log("[metric] name=" + metric.getName()
                            + " type=" + type
                            + " value=" + point.getValue()
                            + " attrs=" + point.getAttributes());
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private void exportHistogram(MetricData metric) {
        Collection<MetricData.HistogramPoint> hpts = metric.getHistogramPoints();
        if (hpts == null || hpts.isEmpty()) return;
        for (MetricData.HistogramPoint hp : hpts) {
            if (hp == null) continue;
            MetricLogger.log("[metric] name=" + metric.getName()
                    + " type=HISTOGRAM"
                    + " count=" + hp.getCount()
                    + " sum=" + hp.getSum()
                    + " min=" + hp.getMin()
                    + " max=" + hp.getMax()
                    + " attrs=" + hp.getAttributes());
        }
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.completedFuture(null);
    }
}

package com.agent.metric;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * OTel 표준을 따르는 메트릭 데이터 모델.
 *
 * <p>HISTOGRAM 타입은 {@link #histogramPoints}로 버킷 데이터를 전달한다.
 * GAUGE / SUM 타입은 {@link #points}를 사용한다.
 */
public final class MetricData {
    private final String name;
    private final String description;
    private final String unit;
    private final MetricType type;
    private final Collection<Point> points;
    private final Collection<HistogramPoint> histogramPoints;

    public enum MetricType {
        GAUGE, SUM, HISTOGRAM
    }

    public MetricData(String name, String description, String unit, MetricType type, Collection<Point> points) {
        this.name            = name;
        this.description     = description;
        this.unit            = unit;
        this.type            = type;
        this.points          = points;
        this.histogramPoints = Collections.emptyList();
    }

    private MetricData(String name, String description, String unit, Collection<HistogramPoint> histogramPoints) {
        this.name            = name;
        this.description     = description;
        this.unit            = unit;
        this.type            = MetricType.HISTOGRAM;
        this.points          = Collections.emptyList();
        this.histogramPoints = histogramPoints;
    }

    /** ExplicitBucketHistogram 데이터를 담는 MetricData 생성. */
    public static MetricData ofHistogram(String name, String description, String unit,
                                         Collection<HistogramPoint> histogramPoints) {
        return new MetricData(name, description, unit, histogramPoints);
    }

    // ---- 개별 데이터 포인트 (Gauge / Sum 타입) ----

    public static final class Point {
        private final double value;
        private final Map<String, String> attributes;
        private final Instant timestamp;

        public Point(double value, Map<String, String> attributes, Instant timestamp) {
            this.value      = value;
            this.attributes = attributes;
            this.timestamp  = timestamp;
        }

        public double              getValue()     { return value; }
        public Map<String, String> getAttributes(){ return attributes; }
        public Instant             getTimestamp() { return timestamp; }
    }

    // ---- ExplicitBucketHistogram 데이터 포인트 ----

    public static final class HistogramPoint {
        private final Map<String, String> attributes;
        private final Instant timestamp;
        private final long count;
        private final double sum;
        private final double min;
        private final double max;
        private final long[] bucketCounts;   // length = boundaries.length + 1 (overflow 포함)
        private final double[] boundaries;   // explicit bucket upper bounds
        private final List<Exemplar> exemplars; // Metric-Trace 연결 (OTel Exemplar)

        public HistogramPoint(Map<String, String> attributes, Instant timestamp,
                              long count, double sum, double min, double max,
                              long[] bucketCounts, double[] boundaries) {
            this(attributes, timestamp, count, sum, min, max, bucketCounts, boundaries,
                 Collections.emptyList());
        }

        public HistogramPoint(Map<String, String> attributes, Instant timestamp,
                              long count, double sum, double min, double max,
                              long[] bucketCounts, double[] boundaries,
                              List<Exemplar> exemplars) {
            this.attributes   = attributes;
            this.timestamp    = timestamp;
            this.count        = count;
            this.sum          = sum;
            this.min          = min;
            this.max          = max;
            this.bucketCounts = bucketCounts;
            this.boundaries   = boundaries;
            this.exemplars    = exemplars != null ? exemplars : Collections.emptyList();
        }

        public Map<String, String> getAttributes()   { return attributes; }
        public Instant             getTimestamp()    { return timestamp; }
        public long                getCount()        { return count; }
        public double              getSum()          { return sum; }
        public double              getMin()          { return min; }
        public double              getMax()          { return max; }
        public long[]              getBucketCounts() { return bucketCounts; }
        public double[]            getBoundaries()   { return boundaries; }
        public List<Exemplar>      getExemplars()    { return exemplars; }
    }

    // ---- Getters ----

    public String                    getName()            { return name; }
    public String                    getDescription()     { return description; }
    public String                    getUnit()            { return unit; }
    public MetricType                getType()            { return type; }
    public Collection<Point>         getPoints()          { return points; }
    public Collection<HistogramPoint> getHistogramPoints(){ return histogramPoints; }
}

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
 * EXPONENTIAL_HISTOGRAM 타입은 {@link #exponentialHistogramPoints}를 사용한다.
 */
public final class MetricData {
    private final String name;
    private final String description;
    private final String unit;
    private final MetricType type;
    private final Collection<Point> points;
    private final Collection<HistogramPoint> histogramPoints;
    private final Collection<ExponentialHistogramPoint> exponentialHistogramPoints;

    public enum MetricType {
        GAUGE, SUM, HISTOGRAM, EXPONENTIAL_HISTOGRAM
    }

    public MetricData(String name, String description, String unit, MetricType type, Collection<Point> points) {
        this.name                       = name;
        this.description                = description;
        this.unit                       = unit;
        this.type                       = type;
        this.points                     = points;
        this.histogramPoints            = Collections.emptyList();
        this.exponentialHistogramPoints = Collections.emptyList();
    }

    private MetricData(String name, String description, String unit, Collection<HistogramPoint> histogramPoints) {
        this.name                       = name;
        this.description                = description;
        this.unit                       = unit;
        this.type                       = MetricType.HISTOGRAM;
        this.points                     = Collections.emptyList();
        this.histogramPoints            = histogramPoints;
        this.exponentialHistogramPoints = Collections.emptyList();
    }

    private MetricData(String name, String description, String unit,
                       Collection<ExponentialHistogramPoint> expHistogramPoints, boolean expHist) {
        this.name                       = name;
        this.description                = description;
        this.unit                       = unit;
        this.type                       = MetricType.EXPONENTIAL_HISTOGRAM;
        this.points                     = Collections.emptyList();
        this.histogramPoints            = Collections.emptyList();
        this.exponentialHistogramPoints = expHistogramPoints;
    }

    /** ExplicitBucketHistogram 데이터를 담는 MetricData 생성. */
    public static MetricData ofHistogram(String name, String description, String unit,
                                         Collection<HistogramPoint> histogramPoints) {
        return new MetricData(name, description, unit, histogramPoints);
    }

    /** ExponentialHistogram 데이터를 담는 MetricData 생성. */
    public static MetricData ofExponentialHistogram(String name, String description, String unit,
                                                     Collection<ExponentialHistogramPoint> points) {
        return new MetricData(name, description, unit, points, true);
    }

    // ---- 개별 데이터 포인트 (Gauge / Sum 타입) ----

    public static final class Point {
        private final double value;
        private final Map<String, String> attributes;
        private final Map<String, Object> typedAttributes;
        private final Instant timestamp;

        /** 기본 생성자 — String 속성 (하위 호환). */
        public Point(double value, Map<String, String> attributes, Instant timestamp) {
            this.value            = value;
            this.attributes       = attributes;
            this.typedAttributes  = null;
            this.timestamp        = timestamp;
        }

        private Point(double value, Instant timestamp, Map<String, Object> typedAttributes) {
            this.value            = value;
            this.attributes       = null;
            this.typedAttributes  = typedAttributes;
            this.timestamp        = timestamp;
        }

        /** 타입 정보를 보존하는 속성(int, double, bool, String)이 필요할 때 사용. */
        public static Point withTypedAttrs(double value, Map<String, Object> typedAttributes, Instant timestamp) {
            return new Point(value, timestamp, typedAttributes);
        }

        public double              getValue()           { return value; }
        public Map<String, String> getAttributes()      { return attributes; }
        public Map<String, Object> getTypedAttributes() { return typedAttributes; }
        public Instant             getTimestamp()       { return timestamp; }
    }

    // ---- ExplicitBucketHistogram 데이터 포인트 ----

    public static final class HistogramPoint {
        private final Map<String, String> attributes;
        private final Map<String, Object> typedAttributes;
        private final Instant timestamp;
        private final long count;
        private final double sum;
        private final double min;
        private final double max;
        private final long[] bucketCounts;   // length = boundaries.length + 1 (overflow 포함)
        private final double[] boundaries;   // explicit bucket upper bounds
        private final List<Exemplar> exemplars;

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
            this(attributes, null, timestamp, count, sum, min, max, bucketCounts, boundaries, exemplars);
        }

        // 마스터 private 생성자 — 타입 소거 충돌 방지를 위해 두 Map 파라미터를 모두 받는다
        private HistogramPoint(Map<String, String> attributes, Map<String, Object> typedAttributes,
                               Instant timestamp, long count, double sum, double min, double max,
                               long[] bucketCounts, double[] boundaries, List<Exemplar> exemplars) {
            this.attributes       = attributes;
            this.typedAttributes  = typedAttributes;
            this.timestamp        = timestamp;
            this.count            = count;
            this.sum              = sum;
            this.min              = min;
            this.max              = max;
            this.bucketCounts     = bucketCounts;
            this.boundaries       = boundaries;
            this.exemplars        = exemplars != null ? exemplars : Collections.emptyList();
        }

        /** 타입 정보를 보존하는 속성이 필요할 때 사용. */
        public static HistogramPoint withTypedAttrs(Map<String, Object> typedAttributes, Instant timestamp,
                                                    long count, double sum, double min, double max,
                                                    long[] bucketCounts, double[] boundaries,
                                                    List<Exemplar> exemplars) {
            return new HistogramPoint(null, typedAttributes, timestamp, count, sum, min, max,
                                      bucketCounts, boundaries, exemplars);
        }

        public Map<String, String> getAttributes()      { return attributes; }
        public Map<String, Object> getTypedAttributes() { return typedAttributes; }
        public Instant             getTimestamp()       { return timestamp; }
        public long                getCount()           { return count; }
        public double              getSum()             { return sum; }
        public double              getMin()             { return min; }
        public double              getMax()             { return max; }
        public long[]              getBucketCounts()    { return bucketCounts; }
        public double[]            getBoundaries()      { return boundaries; }
        public List<Exemplar>      getExemplars()       { return exemplars; }
    }

    // ---- ExponentialHistogram 데이터 포인트 ----

    public static final class ExponentialHistogramPoint {
        private final Map<String, String> attributes;
        private final Instant timestamp;
        private final long count;
        private final double sum;
        private final double min;
        private final double max;
        private final int scale;                    // 버킷 밀도 (음수=넓은 버킷, 양수=좁은 버킷)
        private final long zeroCount;               // 0 값 개수
        private final double zeroThreshold;         // 0-region 경계 (기본 0)
        private final long[] positiveBucketCounts;  // 양수 범위 버킷 카운트
        private final int positiveOffset;           // 양수 버킷 시작 인덱스 (sint32)
        private final long[] negativeBucketCounts;  // 음수 범위 버킷 카운트
        private final int negativeOffset;           // 음수 버킷 시작 인덱스 (sint32)
        private final List<Exemplar> exemplars;

        public ExponentialHistogramPoint(Map<String, String> attributes, Instant timestamp,
                                         long count, double sum, double min, double max,
                                         int scale, long zeroCount,
                                         long[] positiveBucketCounts, int positiveOffset,
                                         long[] negativeBucketCounts, int negativeOffset) {
            this(attributes, timestamp, count, sum, min, max, scale, zeroCount, 0.0,
                 positiveBucketCounts, positiveOffset, negativeBucketCounts, negativeOffset,
                 Collections.emptyList());
        }

        public ExponentialHistogramPoint(Map<String, String> attributes, Instant timestamp,
                                         long count, double sum, double min, double max,
                                         int scale, long zeroCount, double zeroThreshold,
                                         long[] positiveBucketCounts, int positiveOffset,
                                         long[] negativeBucketCounts, int negativeOffset,
                                         List<Exemplar> exemplars) {
            this.attributes           = attributes;
            this.timestamp            = timestamp;
            this.count                = count;
            this.sum                  = sum;
            this.min                  = min;
            this.max                  = max;
            this.scale                = scale;
            this.zeroCount            = zeroCount;
            this.zeroThreshold        = zeroThreshold;
            this.positiveBucketCounts = positiveBucketCounts != null ? positiveBucketCounts : new long[0];
            this.positiveOffset       = positiveOffset;
            this.negativeBucketCounts = negativeBucketCounts != null ? negativeBucketCounts : new long[0];
            this.negativeOffset       = negativeOffset;
            this.exemplars            = exemplars != null ? exemplars : Collections.emptyList();
        }

        public Map<String, String> getAttributes()           { return attributes; }
        public Instant             getTimestamp()            { return timestamp; }
        public long                getCount()                { return count; }
        public double              getSum()                  { return sum; }
        public double              getMin()                  { return min; }
        public double              getMax()                  { return max; }
        public int                 getScale()                { return scale; }
        public long                getZeroCount()            { return zeroCount; }
        public double              getZeroThreshold()        { return zeroThreshold; }
        public long[]              getPositiveBucketCounts() { return positiveBucketCounts; }
        public int                 getPositiveOffset()       { return positiveOffset; }
        public long[]              getNegativeBucketCounts() { return negativeBucketCounts; }
        public int                 getNegativeOffset()       { return negativeOffset; }
        public List<Exemplar>      getExemplars()            { return exemplars; }
    }

    // ---- Getters ----

    public String                              getName()                       { return name; }
    public String                              getDescription()                { return description; }
    public String                              getUnit()                       { return unit; }
    public MetricType                          getType()                       { return type; }
    public Collection<Point>                   getPoints()                     { return points; }
    public Collection<HistogramPoint>          getHistogramPoints()            { return histogramPoints; }
    public Collection<ExponentialHistogramPoint> getExponentialHistogramPoints() { return exponentialHistogramPoints; }
}

package com.agent.metric;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * OTel 표준을 따르는 메트릭 데이터 모델.
 */
public final class MetricData {
    private final String name;
    private final String description;
    private final String unit;
    private final MetricType type;
    private final Collection<Point> points;

    public enum MetricType {
        GAUGE, SUM, HISTOGRAM
    }

    public MetricData(String name, String description, String unit, MetricType type, Collection<Point> points) {
        this.name = name;
        this.description = description;
        this.unit = unit;
        this.type = type;
        this.points = points;
    }

    /** 개별 데이터 포인트 (값 + 속성 + 시간) */
    public static final class Point {
        private final double value;
        private final Map<String, String> attributes;
        private final Instant timestamp;

        public Point(double value, Map<String, String> attributes, Instant timestamp) {
            this.value = value;
            this.attributes = attributes;
            this.timestamp = timestamp;
        }

        public double getValue() { return value; }
        public Map<String, String> getAttributes() { return attributes; }
        public Instant getTimestamp() { return timestamp; }
    }

    // Getters
    public String getName() { return name; }
    public MetricType getType() { return type; }
    public Collection<Point> getPoints() { return points; }
}

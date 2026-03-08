package com.agent.metric;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 메트릭 이름과 속성(Attributes)의 조합으로 메트릭을 고유하게 식별한다.
 */
public final class MetricKey {
    private final String name;
    private final Map<String, String> attributes;

    public MetricKey(String name, Map<String, String> attributes) {
        this.name = name;
        this.attributes = attributes != null ? new TreeMap<>(attributes) : new TreeMap<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetricKey metricKey = (MetricKey) o;
        return Objects.equals(name, metricKey.name) && Objects.equals(attributes, metricKey.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, attributes);
    }

    public String getName() { return name; }
    public Map<String, String> getAttributes() { return attributes; }
}

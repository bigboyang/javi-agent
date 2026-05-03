package com.agent.metric;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 메트릭 이름과 속성(Attributes)의 조합으로 메트릭을 고유하게 식별한다.
 *
 * <p>주의: 전달된 attributes 맵은 복사하지 않는다. 호출자는 MetricKey 생성 후
 * 해당 맵을 수정해서는 안 된다 (불변 맵 또는 전용 인스턴스를 전달할 것).
 */
public final class MetricKey {
    private final String name;
    private final Map<String, String> attributes;

    public MetricKey(String name, Map<String, String> attributes) {
        this.name = name;
        this.attributes = attributes != null ? attributes : Collections.emptyMap();
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

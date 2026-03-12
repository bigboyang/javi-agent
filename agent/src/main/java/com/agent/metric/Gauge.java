package com.agent.metric;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 특정 시점의 측정값(예: 메모리 사용량)을 나타내는 게이지.
 */
public final class Gauge {
    private final String name;
    private final String description;
    private final String unit;
    private final Map<String, String> attributes;
    private final AtomicLong value = new AtomicLong(0);

    Gauge(String name, String description, String unit, Map<String, String> attributes) {
        this.name = name;
        this.description = description != null ? description : "";
        this.unit = unit != null ? unit : "1";
        this.attributes = attributes;
    }

    public void set(long val) { value.set(val); }
    public long get() { return value.get(); }
    
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getUnit() { return unit; }
    public Map<String, String> getAttributes() { return attributes; }
}

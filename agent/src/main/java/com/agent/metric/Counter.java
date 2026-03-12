package com.agent.metric;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * OTel/DataDog 스타일의 다차원 카운터.
 */
public final class Counter {
    private final String name;
    private final String description;
    private final String unit;
    private final Map<String, String> attributes;
    private final LongAdder value = new LongAdder();

    Counter(String name, String description, String unit, Map<String, String> attributes) {
        this.name = name;
        this.description = description != null ? description : "";
        this.unit = unit != null ? unit : "1";
        this.attributes = attributes;
    }

    public void increment() { value.increment(); }
    public void add(long delta) { if (delta > 0) value.add(delta); }
    public long get() { return value.sum(); }
    
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getUnit() { return unit; }
    public Map<String, String> getAttributes() { return attributes; }
}

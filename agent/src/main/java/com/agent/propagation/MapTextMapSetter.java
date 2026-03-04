package com.agent.propagation;

import java.util.Map;

/** TextMapSetter for Map-based carriers (e.g., HTTP headers). */
public final class MapTextMapSetter implements TextMapSetter<Map<String, String>> {
    @Override
    public void set(Map<String, String> carrier, String key, String value) {
        if (carrier == null || key == null || value == null) {
            return;
        }
        carrier.put(key, value);
    }
}

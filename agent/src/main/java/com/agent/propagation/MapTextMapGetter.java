package com.agent.propagation;

import java.util.Map;

/** TextMapGetter for Map-based carriers (e.g., HTTP headers). */
public final class MapTextMapGetter implements TextMapGetter<Map<String, String>> {
    @Override
    public String get(Map<String, String> carrier, String key) {
        if (carrier == null || key == null) {
            return null;
        }
        return carrier.get(key);
    }
}

package com.agent.propagation;

import java.util.Map;

/**
 * W3C Baggage 표준 규격을 준수하는 전파기.
 * 헤더 형식: baggage: user_tier=gold,tenant_id=123
 */
public final class W3CBaggagePropagator implements BaggagePropagator {

    private static final String BAGGAGE_HEADER = "baggage";

    @Override
    public <C> void inject(Baggage baggage, C carrier, TextMapSetter<C> setter) {
        if (baggage == null || baggage.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : baggage.asMap().entrySet()) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        setter.set(carrier, BAGGAGE_HEADER, sb.toString());
    }

    @Override
    public <C> Baggage extract(C carrier, TextMapGetter<C> getter) {
        String header = getter.get(carrier, BAGGAGE_HEADER);
        if (header == null || header.isEmpty()) {
            return Baggage.empty();
        }

        Baggage.Builder builder = Baggage.builder();
        String[] parts = header.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                builder.put(kv[0].trim(), kv[1].trim());
            }
        }
        return builder.build();
    }
}

package com.agent.propagation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 비즈니스 맥락(User Tier, Tenant ID 등)을 트레이스 전체 구간에 전파하기 위한 컨테이너.
 * W3C Baggage 표준을 준수한다.
 */
public final class Baggage {

    private static final Baggage EMPTY = new Baggage(Collections.emptyMap());

    private final Map<String, String> values;

    private Baggage(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public static Baggage empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String get(String key) {
        return values.get(key);
    }

    public Map<String, String> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public static final class Builder {
        private final Map<String, String> values = new HashMap<>();

        public Builder put(String key, String value) {
            if (key != null && value != null) {
                values.put(key, value);
            }
            return this;
        }

        public Baggage build() {
            if (values.isEmpty()) {
                return EMPTY;
            }
            return new Baggage(values);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Baggage baggage = (Baggage) o;
        return Objects.equals(values, baggage.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values);
    }

    @Override
    public String toString() {
        return "Baggage{" + values + '}';
    }
}

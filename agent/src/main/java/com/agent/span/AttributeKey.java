package com.agent.span;

import java.util.Objects;

/**
 * 타입 안정성을 위한 Attribute 키.
 * equals/hashCode를 구현하여 Map에서 올바르게 동작함.
 */
public final class AttributeKey<T> {
    public enum Type { STRING, LONG, DOUBLE, BOOLEAN }

    private final String key;
    private final Type type;

    private AttributeKey(String key, Type type) {
        this.key = key;
        this.type = type;
    }

    public static AttributeKey<String> stringKey(String key) {
        return new AttributeKey<>(key, Type.STRING);
    }

    public static AttributeKey<Long> longKey(String key) {
        return new AttributeKey<>(key, Type.LONG);
    }

    public static AttributeKey<Double> doubleKey(String key) {
        return new AttributeKey<>(key, Type.DOUBLE);
    }

    public static AttributeKey<Boolean> booleanKey(String key) {
        return new AttributeKey<>(key, Type.BOOLEAN);
    }

    public String getKey() {
        return key;
    }

    public Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttributeKey)) return false;
        AttributeKey<?> that = (AttributeKey<?>) o;
        return type == that.type && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, type);
    }

    @Override
    public String toString() {
        return type.name().toLowerCase() + ":" + key;
    }
}

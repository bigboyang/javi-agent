package com.agent.span;

/**
 * 타입 안정성을 위한 Attribute 키.
 */
public final class AttributeKey<T> {
    private final String key;

    private AttributeKey(String key) {
        this.key = key;
    }

    public static AttributeKey<String> stringKey(String key) {
        return new AttributeKey<>(key);
    }

    public static AttributeKey<Long> longKey(String key) {
        return new AttributeKey<>(key);
    }

    public static AttributeKey<Double> doubleKey(String key) {
        return new AttributeKey<>(key);
    }

    public static AttributeKey<Boolean> booleanKey(String key) {
        return new AttributeKey<>(key);
    }

    public String getKey() {
        return key;
    }
}

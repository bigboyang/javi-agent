package com.agent.span;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 타입 안정성을 위한 Attribute 키.
 *
 * <p>인터닝 설계 (OTel Java SDK 방식):
 * <ul>
 *   <li>타입별 ConcurrentHashMap 캐시로 동일 키 문자열에 대해 항상 같은 인스턴스 반환</li>
 *   <li>hot path(setAttribute)에서 매 호출마다 new 객체 생성 제거</li>
 *   <li>hashCode를 생성자에서 미리 계산하여 Map 조회 비용 최소화</li>
 * </ul>
 */
public final class AttributeKey<T> {
    public enum Type { STRING, LONG, DOUBLE, BOOLEAN }

    // ── 타입별 인터닝 캐시 (OTel Java SDK 동일 패턴) ──────────────────────────
    private static final ConcurrentHashMap<String, AttributeKey<String>>  STRING_CACHE  = new ConcurrentHashMap<>(64);
    private static final ConcurrentHashMap<String, AttributeKey<Long>>    LONG_CACHE    = new ConcurrentHashMap<>(16);
    private static final ConcurrentHashMap<String, AttributeKey<Double>>  DOUBLE_CACHE  = new ConcurrentHashMap<>(8);
    private static final ConcurrentHashMap<String, AttributeKey<Boolean>> BOOLEAN_CACHE = new ConcurrentHashMap<>(8);

    private final String key;
    private final Type   type;
    // 생성자에서 미리 계산 — ConcurrentHashMap 조회 시 재계산 없음
    private final int    cachedHashCode;

    private AttributeKey(String key, Type type) {
        this.key            = key;
        this.type           = type;
        this.cachedHashCode = Objects.hash(key, type);
    }

    public static AttributeKey<String> stringKey(String key) {
        return STRING_CACHE.computeIfAbsent(key, k -> new AttributeKey<>(k, Type.STRING));
    }

    public static AttributeKey<Long> longKey(String key) {
        return LONG_CACHE.computeIfAbsent(key, k -> new AttributeKey<>(k, Type.LONG));
    }

    public static AttributeKey<Double> doubleKey(String key) {
        return DOUBLE_CACHE.computeIfAbsent(key, k -> new AttributeKey<>(k, Type.DOUBLE));
    }

    public static AttributeKey<Boolean> booleanKey(String key) {
        return BOOLEAN_CACHE.computeIfAbsent(key, k -> new AttributeKey<>(k, Type.BOOLEAN));
    }

    public String getKey() { return key; }
    public Type   getType(){ return type; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttributeKey)) return false;
        AttributeKey<?> that = (AttributeKey<?>) o;
        return type == that.type && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return cachedHashCode;
    }

    @Override
    public String toString() {
        return type.name().toLowerCase() + ":" + key;
    }
}

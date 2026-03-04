package com.agent.propagation;

/**
 * Carrier에서 값을 읽기 위한 인터페이스.
 */
public interface TextMapGetter<C> {
    String get(C carrier, String key);
}

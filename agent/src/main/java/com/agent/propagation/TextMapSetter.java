package com.agent.propagation;

/**
 * Carrier에 값을 주입하기 위한 인터페이스.
 */
public interface TextMapSetter<C> {
    void set(C carrier, String key, String value);
}

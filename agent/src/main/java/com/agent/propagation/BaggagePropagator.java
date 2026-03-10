package com.agent.propagation;

/**
 * Baggage(비즈니스 맥락)를 주입하고 추출하기 위한 인터페이스.
 */
public interface BaggagePropagator {
    <C> void inject(Baggage baggage, C carrier, TextMapSetter<C> setter);

    <C> Baggage extract(C carrier, TextMapGetter<C> getter);
}

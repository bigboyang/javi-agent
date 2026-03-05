package com.agent.trace;

import com.agent.common.utils.generator.IdGenerator;
import com.agent.sampler.Sampler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SdkTracerProviderTest {

    @Test
    void getTracerReturnsCachedInstance() {
        SdkTracerProvider provider = new SdkTracerProvider(IdGenerator.random());
        Tracer t1 = provider.getTracer("my-service");
        Tracer t2 = provider.getTracer("my-service");
        assertSame(t1, t2, "같은 이름이면 동일 인스턴스를 반환해야 함");
    }

    @Test
    void getTracerDifferentNamesReturnDifferentInstances() {
        SdkTracerProvider provider = new SdkTracerProvider(IdGenerator.random());
        Tracer t1 = provider.getTracer("service-a");
        Tracer t2 = provider.getTracer("service-b");
        assertNotSame(t1, t2);
    }

    @Test
    void getTracerDisabledReturnsNoop() {
        SdkTracerProvider provider = new SdkTracerProvider(
                IdGenerator.random(), TracerConfig.disabled());
        Tracer tracer = provider.getTracer("x");
        assertSame(NoopTracer.INSTANCE, tracer);
    }

    @Test
    void setSamplerApplied() {
        SdkTracerProvider provider = new SdkTracerProvider(IdGenerator.random());
        provider.setSampler(Sampler.alwaysOff());
        // 스팬 생성 시 DROP 되는지 확인
        Tracer tracer = provider.getTracer("svc");
        var span = tracer.spanBuilder("op").startSpan();
        // alwaysOff → Span.invalid() → NoopSpan → isRecording=false
        assertFalse(span.isRecording(), "AlwaysOff 샘플러면 스팬이 recording 상태가 아니어야 함");
    }

    @Test
    void setTracerConfigDisabledClearsCache() {
        SdkTracerProvider provider = new SdkTracerProvider(IdGenerator.random());
        Tracer before = provider.getTracer("svc");
        assertNotNull(before); // just get instance to warm cache

        provider.setTracerConfig(TracerConfig.disabled());
        Tracer disabled = provider.getTracer("svc");
        assertSame(NoopTracer.INSTANCE, disabled, "config 변경 후 NoopTracer 반환");
    }

    @Test
    void shutdownPreventsNewSpans() {
        SdkTracerProvider provider = new SdkTracerProvider(IdGenerator.random());
        provider.shutdown();

        Tracer tracer = provider.getTracer("svc");
        var span = tracer.spanBuilder("op").startSpan();
        assertFalse(span.isRecording(), "shutdown 후 스팬은 recording 상태가 아니어야 함");
    }
}

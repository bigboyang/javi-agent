package com.agent.trace.processor;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.common.utils.generator.IdGenerator;
import com.agent.span.Span;
import com.agent.trace.SdkTracerProvider;
import com.agent.trace.Tracer;
import com.agent.trace.exporter.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BatchSpanProcessorTest {

    private BatchSpanProcessor processor;

    @AfterEach
    void tearDown() {
        if (processor != null) {
            processor.shutdown().join(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void onEndQueuesSpan() {
        List<Span> received = Collections.synchronizedList(new ArrayList<>());
        SpanExporter exporter = spans -> {
            received.addAll(spans);
            return CompletableResultCode.ofSuccess();
        };

        processor = new BatchSpanProcessor(exporter, 100, 10, 50);
        processor.onEnd(buildRecordingSpan("op1"));
        processor.onEnd(buildRecordingSpan("op2"));

        processor.forceFlush().join(3, TimeUnit.SECONDS);
        assertEquals(2, received.size());
    }

    @Test
    void queueFullDropsSpan() {
        List<Span> received = Collections.synchronizedList(new ArrayList<>());
        SpanExporter exporter = spans -> {
            received.addAll(spans);
            return CompletableResultCode.ofSuccess();
        };

        // maxQueueSize=2, export delay=10s (scheduled export 실행 안 됨)
        processor = new BatchSpanProcessor(exporter, 2, 2, 10_000);
        processor.onEnd(buildRecordingSpan("a"));
        processor.onEnd(buildRecordingSpan("b"));
        processor.onEnd(buildRecordingSpan("c")); // 큐 꽉 참 → 드롭

        processor.forceFlush().join(3, TimeUnit.SECONDS);
        assertEquals(2, received.size(), "큐 크기 초과분은 드롭되어야 함");
    }

    @Test
    void shutdownFlushesPendingSpans() {
        List<Span> received = Collections.synchronizedList(new ArrayList<>());
        SpanExporter exporter = spans -> {
            received.addAll(spans);
            return CompletableResultCode.ofSuccess();
        };

        // 긴 delay로 scheduled export는 실행 안 됨
        processor = new BatchSpanProcessor(exporter, 100, 10, 10_000);
        processor.onEnd(buildRecordingSpan("pending"));

        CompletableResultCode result = processor.shutdown();
        result.join(5, TimeUnit.SECONDS);

        assertEquals(1, received.size(), "shutdown 시 pending 스팬이 flush 되어야 함");
    }

    @Test
    void onEndAfterShutdownIgnored() {
        List<Span> received = Collections.synchronizedList(new ArrayList<>());
        SpanExporter exporter = spans -> {
            received.addAll(spans);
            return CompletableResultCode.ofSuccess();
        };

        processor = new BatchSpanProcessor(exporter, 100, 10, 10_000);
        processor.shutdown().join(3, TimeUnit.SECONDS);

        processor.onEnd(buildRecordingSpan("after-shutdown")); // 무시되어야 함
        processor.forceFlush().join(1, TimeUnit.SECONDS);

        assertEquals(0, received.size(), "shutdown 이후 스팬은 무시되어야 함");
    }

    /**
     * SdkTracerProvider + 수동 종료 없는 BatchSpanProcessor를 통해
     * 실제 SdkSpan 인스턴스를 생성한다.
     */
    private Span buildRecordingSpan(String name) {
        SdkTracerProvider provider = new SdkTracerProvider(IdGenerator.random());
        Tracer tracer = provider.getTracer("test");
        Span span = tracer.spanBuilder(name).startSpan();
        span.end();
        return span;
    }
}

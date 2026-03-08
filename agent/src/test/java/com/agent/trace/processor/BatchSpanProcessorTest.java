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
            // 전송 속도를 늦춰서 큐가 차도록 유도
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            received.addAll(spans);
            return CompletableResultCode.ofSuccess();
        };

        // maxQueueSize=5, export delay=10s
        // 많은 양을 한꺼번에 밀어넣으면 큐가 차서 드롭이 발생함
        int maxQueueSize = 5;
        processor = new BatchSpanProcessor(exporter, maxQueueSize, 10, 10_000);
        
        for (int i = 0; i < 20; i++) {
            processor.onEnd(buildRecordingSpan("span-" + i));
        }

        processor.forceFlush().join(3, TimeUnit.SECONDS);
        // 전체 20개를 넣었으나 큐 크기 제한으로 인해 일부는 드롭되어야 함
        assertTrue(received.size() < 20, 
            "큐 크기 제한으로 인해 일부 스팬은 드롭되어야 함 (received: " + received.size() + ")");
        assertTrue(received.size() >= 5,
            "최소 큐 크기만큼은 수집되어야 함 (received: " + received.size() + ")");
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

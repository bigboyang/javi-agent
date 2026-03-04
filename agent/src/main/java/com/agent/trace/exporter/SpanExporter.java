package com.agent.trace.exporter;

import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.span.Span;
import java.io.Closeable;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * 완료된 Span을 외부로 내보내는 인터페이스.
 */
public interface SpanExporter extends Closeable {
    CompletableResultCode export(Collection<Span> spans);

    default CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    default CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    default void close() {
        shutdown().join(10, TimeUnit.SECONDS);
    }
}

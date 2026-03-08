package com.agent.common;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * 수집된 데이터를 외부(Collector 등)로 전송하는 인터페이스.
 * @param <T> Span, LogRecord, Metric 등 데이터 타입
 */
public interface DataExporter<T> {
    /**
     * 배치 단위의 데이터를 전송한다.
     */
    CompletableFuture<Void> export(Collection<T> items);

    /**
     * 리소스 정리 및 종료.
     */
    CompletableFuture<Void> shutdown();
}

package com.agent.logs;

import com.agent.common.DataExporter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

/**
 * 여러 LogExporter에 동시에 데이터를 전달하는 합성 익스포터.
 *
 * <p>dispatchExecutor는 인스턴스 필드로 관리되며, shutdown() 시 정리된다.
 * static 필드로 선언하면 멀티 ClassLoader 환경(EE, OSGi)에서 메모리 누수가 발생한다.
 */
public final class CompositeLogExporter implements DataExporter<LogRecord> {

    private final ExecutorService dispatchExecutor = new ThreadPoolExecutor(
            2, Math.max(4, Runtime.getRuntime().availableProcessors()),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            r -> { Thread t = new Thread(r, "javi-composite-log-export"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final List<DataExporter<LogRecord>> exporters;

    private CompositeLogExporter(List<DataExporter<LogRecord>> exporters) {
        this.exporters = exporters;
    }

    @SafeVarargs
    public static CompositeLogExporter of(DataExporter<LogRecord>... exporters) {
        return new CompositeLogExporter(Arrays.asList(exporters));
    }

    @Override
    public CompletableFuture<Void> export(Collection<LogRecord> items) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(exporters.size());
        for (DataExporter<LogRecord> exporter : exporters) {
            futures.add(
                CompletableFuture.supplyAsync(() -> exporter.export(items), dispatchExecutor)
                    .thenCompose(f -> f)
                    .exceptionally(e -> null)
            );
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        List<CompletableFuture<Void>> futures = new ArrayList<>(exporters.size());
        for (DataExporter<LogRecord> exporter : exporters) {
            futures.add(
                CompletableFuture.supplyAsync(() -> exporter.shutdown(), dispatchExecutor)
                    .thenCompose(f -> f)
                    .exceptionally(e -> null)
            );
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(dispatchExecutor::shutdown);
    }
}

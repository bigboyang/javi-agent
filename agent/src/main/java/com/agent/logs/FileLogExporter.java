package com.agent.logs;

import com.agent.common.DataExporter;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * 수집된 로그 레코드를 파일(또는 나중에 OTLP)로 내보내는 구현체.
 */
public class FileLogExporter implements DataExporter<LogRecord> {

    @Override
    public CompletableFuture<Void> export(Collection<LogRecord> logs) {
        // 실제 운영 환경에서는 JSON 포맷 등으로 구조화하여 파일에 저장하거나
        // HTTP를 통해 Collector로 직접 전송한다.
        for (LogRecord log : logs) {
            LogLogger.log("[LogExport] " + log.getSeverity() + " " + log.getBody() + " (traceId=" + log.getTraceId() + ")");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.completedFuture(null);
    }
}

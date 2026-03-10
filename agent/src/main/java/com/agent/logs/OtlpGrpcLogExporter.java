package com.agent.logs;

import com.agent.common.DataExporter;
import com.agent.common.grpc.GrpcSender;
import com.agent.common.grpc.GrpcSender.SendResult;
import com.agent.common.grpc.ProtoEncoder;
import com.agent.common.ResourceInfo;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LogRecord를 OTLP/gRPC protobuf 포맷으로 OTel Collector에 전송하는 Exporter.
 *
 * <p>gRPC 서비스 경로:
 * {@code /opentelemetry.proto.collector.logs.v1.LogsService/Export}
 *
 * <p>LogRecord proto field numbers (opentelemetry-proto/logs/v1/logs.proto):
 * <ul>
 *   <li>time_unix_nano = 1 (fixed64)</li>
 *   <li>severity_number = 2 (varint SeverityNumber enum)</li>
 *   <li>severity_text = 3 (string)</li>
 *   <li>body = 15 (AnyValue LEN)</li>
 *   <li>attributes = 6 (repeated KeyValue)</li>
 *   <li>trace_id = 9 (bytes/16)</li>
 *   <li>span_id = 10 (bytes/8)</li>
 * </ul>
 *
 * <p>설정:
 * <ul>
 *   <li>JAVI_GRPC_ENDPOINT / javi.grpc.endpoint (기본: http://localhost:4317)</li>
 *   <li>JAVI_SERVICE_NAME / javi.service.name (기본: javi-service)</li>
 * </ul>
 */
public final class OtlpGrpcLogExporter implements DataExporter<LogRecord> {

    private static final String GRPC_PATH =
            "/opentelemetry.proto.collector.logs.v1.LogsService/Export";

    // ---- LogRecord proto field numbers ----
    private static final int FN_LOG_TIME_NS       = 1;  // fixed64
    private static final int FN_LOG_SEVERITY_NUM  = 2;  // varint
    private static final int FN_LOG_SEVERITY_TEXT = 3;  // string
    private static final int FN_LOG_BODY          = 15; // AnyValue
    private static final int FN_LOG_ATTRS         = 6;  // repeated KeyValue
    private static final int FN_LOG_TRACE_ID      = 9;  // bytes
    private static final int FN_LOG_SPAN_ID       = 10; // bytes

    // Top-level wrapper field numbers
    private static final int FN_RESOURCE_LOGS = 1;  // ExportLogsServiceRequest
    private static final int FN_RL_RESOURCE   = 1;  // ResourceLogs.resource
    private static final int FN_RL_SCOPE_LOGS = 2;  // ResourceLogs.scope_logs
    private static final int FN_SL_SCOPE      = 1;  // ScopeLogs.scope
    private static final int FN_SL_RECORDS    = 2;  // ScopeLogs.log_records
    private static final int FN_SCOPE_NAME    = 1;
    private static final int FN_SCOPE_VERSION = 2;
    private static final int FN_RESOURCE_ATTRS = 1;

    // KeyValue / AnyValue
    private static final int FN_KV_KEY    = 1;
    private static final int FN_KV_VALUE  = 2;
    private static final int FN_AV_STRING = 1;

    // ---- 내부 상태 ----
    private final GrpcSender sender;
    private final String serviceName;
    private final AtomicBoolean isShutdown  = new AtomicBoolean(false);
    private final AtomicLong exportedCount  = new AtomicLong(0);
    private final AtomicLong droppedCount   = new AtomicLong(0);
    private final AtomicLong failedBatches  = new AtomicLong(0);

    // ---- 생성자 ----

    public OtlpGrpcLogExporter() {
        this(resolveServiceName(), GrpcSender.create());
    }

    public OtlpGrpcLogExporter(String serviceName, GrpcSender sender) {
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    // ---- DataExporter<LogRecord> 구현 ----

    @Override
    public CompletableFuture<Void> export(Collection<LogRecord> logs) {
        if (isShutdown.get() || logs == null || logs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            byte[] protoBytes = encodeExportRequest(logs, serviceName);
            SendResult result = sender.send(GRPC_PATH, protoBytes);

            if (result == SendResult.SUCCESS) {
                exportedCount.addAndGet(logs.size());
            } else {
                droppedCount.addAndGet(logs.size());
                failedBatches.incrementAndGet();
                AgentLogger.warn("OtlpGrpcLogExporter: 배치 전송 실패 logs=" + logs.size()
                        + " dropped_total=" + droppedCount.get());
            }
        } catch (Exception e) {
            AgentLogger.warn("OtlpGrpcLogExporter: 인코딩 오류 — " + e.getMessage());
            droppedCount.addAndGet(logs.size());
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            AgentLogger.info("OtlpGrpcLogExporter shutdown: exported=" + exportedCount.get()
                    + " dropped=" + droppedCount.get()
                    + " failed_batches=" + failedBatches.get());
        }
        return CompletableFuture.completedFuture(null);
    }

    // ---- Protobuf 인코딩 ----

    static byte[] encodeExportRequest(Collection<LogRecord> logs, String serviceName) {
        byte[] resourceBytes = encodeResource(serviceName);

        ByteArrayOutputStream recordsOut = new ByteArrayOutputStream(logs.size() * 200);
        for (LogRecord log : logs) {
            if (log == null) continue;
            byte[] recBytes = encodeLogRecord(log);
            ProtoEncoder.writeMessage(recordsOut, FN_SL_RECORDS, recBytes);
        }

        ByteArrayOutputStream scopeOut = new ByteArrayOutputStream(32);
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_NAME, "javi-log");
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_VERSION, "1.0.0");

        ByteArrayOutputStream scopeLogsOut = new ByteArrayOutputStream(64 + recordsOut.size());
        ProtoEncoder.writeMessage(scopeLogsOut, FN_SL_SCOPE, scopeOut.toByteArray());
        byte[] recBytes = recordsOut.toByteArray();
        scopeLogsOut.write(recBytes, 0, recBytes.length);

        ByteArrayOutputStream rlOut = new ByteArrayOutputStream(128 + scopeLogsOut.size());
        ProtoEncoder.writeMessage(rlOut, FN_RL_RESOURCE, resourceBytes);
        ProtoEncoder.writeMessage(rlOut, FN_RL_SCOPE_LOGS, scopeLogsOut.toByteArray());

        ByteArrayOutputStream requestOut = new ByteArrayOutputStream(rlOut.size() + 4);
        ProtoEncoder.writeMessage(requestOut, FN_RESOURCE_LOGS, rlOut.toByteArray());
        return requestOut.toByteArray();
    }

    private static byte[] encodeResource(String serviceName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        // ResourceInfo에서 동적으로 모든 속성 가져오기
        for (Map.Entry<String, String> entry : ResourceInfo.getAttributes().entrySet()) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV(entry.getKey(), entry.getValue()));
        }

        // service.name 보장
        if (!ResourceInfo.getAttributes().containsKey("service.name")) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("service.name", serviceName));
        }

        return out.toByteArray();
    }

    private static byte[] encodeLogRecord(LogRecord log) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        // time_unix_nano
        if (log.getTimestamp() != null) {
            long nanos = log.getTimestamp().getEpochSecond() * 1_000_000_000L
                       + log.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_LOG_TIME_NS, nanos);
        }

        // severity_number + severity_text
        String sev = log.getSeverity() != null ? log.getSeverity().toUpperCase() : "UNSPECIFIED";
        int severityNum = severityToNumber(sev);
        if (severityNum != 0) ProtoEncoder.writeVarint32(out, FN_LOG_SEVERITY_NUM, severityNum);
        ProtoEncoder.writeString(out, FN_LOG_SEVERITY_TEXT, log.getSeverity());

        // body = AnyValue{string_value: body}
        if (log.getBody() != null) {
            ByteArrayOutputStream bodyAv = new ByteArrayOutputStream(log.getBody().length() + 4);
            ProtoEncoder.writeString(bodyAv, FN_AV_STRING, log.getBody());
            ProtoEncoder.writeMessage(out, FN_LOG_BODY, bodyAv.toByteArray());
        }

        // attributes (logger.name + custom attrs)
        if (log.getLoggerName() != null) {
            byte[] kv = encodeStringKV("logger.name", log.getLoggerName());
            ProtoEncoder.writeMessage(out, FN_LOG_ATTRS, kv);
        }
        Map<String, String> attrs = log.getAttributes();
        if (attrs != null) {
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                byte[] kv = encodeStringKV(entry.getKey(), entry.getValue());
                ProtoEncoder.writeMessage(out, FN_LOG_ATTRS, kv);
            }
        }

        // trace_id / span_id (hex -> bytes)
        byte[] traceId = ProtoEncoder.hexToBytes(log.getTraceId());
        byte[] spanId  = ProtoEncoder.hexToBytes(log.getSpanId());
        if (traceId != null) ProtoEncoder.writeBytes(out, FN_LOG_TRACE_ID, traceId);
        if (spanId  != null) ProtoEncoder.writeBytes(out, FN_LOG_SPAN_ID,  spanId);

        return out.toByteArray();
    }

    private static byte[] encodeStringKV(String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(key.length() + (value != null ? value.length() : 0) + 8);
        ProtoEncoder.writeString(out, FN_KV_KEY, key);
        ByteArrayOutputStream av = new ByteArrayOutputStream((value != null ? value.length() : 0) + 4);
        ProtoEncoder.writeString(av, FN_AV_STRING, value != null ? value : "");
        ProtoEncoder.writeMessage(out, FN_KV_VALUE, av.toByteArray());
        return out.toByteArray();
    }

    /**
     * OTel SeverityNumber 매핑.
     * TRACE=1, DEBUG=5, INFO=9, WARN=13, ERROR=17, FATAL=21
     */
    private static int severityToNumber(String severity) {
        if (severity == null) return 0;
        switch (severity) {
            case "TRACE":   return 1;
            case "DEBUG":   return 5;
            case "INFO":    return 9;
            case "WARN":
            case "WARNING": return 13;
            case "ERROR":   return 17;
            case "FATAL":   return 21;
            default:        return 0;
        }
    }

    private static String resolveServiceName() {
        String val = System.getenv("JAVI_SERVICE_NAME");
        if (val != null && !val.isEmpty()) return val;
        val = System.getProperty("javi.service.name");
        if (val != null && !val.isEmpty()) return val;
        return "javi-service";
    }
}

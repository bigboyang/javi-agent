package com.agent.logs;

import com.agent.common.DataExporter;
import com.agent.common.OtlpHttpProtobufSender;
import com.agent.common.OtlpHttpProtobufSender.SendResult;
import com.agent.common.ProtoEncoder;
import com.agent.common.ResourceInfo;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LogRecord를 OTLP/HTTP Protobuf 포맷으로 OTel Collector에 전송하는 Exporter.
 */
public final class OtlpHttpLogExporter implements DataExporter<LogRecord> {

    private static final String LOG_PATH = "/v1/logs";

    // ---- LogRecord proto field numbers ----
    private static final int FN_LOG_TIME_NS            = 1;
    private static final int FN_LOG_SEVERITY_NUM       = 2;
    private static final int FN_LOG_SEVERITY_TEXT      = 3;
    private static final int FN_LOG_BODY               = 5;
    private static final int FN_LOG_ATTRS              = 6;
    private static final int FN_LOG_FLAGS              = 8;   // fixed32: W3C trace-flags
    private static final int FN_LOG_TRACE_ID           = 9;
    private static final int FN_LOG_SPAN_ID            = 10;
    private static final int FN_LOG_OBSERVED_TIME_NS   = 11;  // fixed64: when agent observed the log

    private static final int FN_RESOURCE_LOGS  = 1;
    private static final int FN_RL_RESOURCE    = 1;
    private static final int FN_RL_SCOPE_LOGS  = 2;
    private static final int FN_RL_SCHEMA_URL  = 3;
    private static final int FN_SL_SCOPE       = 1;
    private static final int FN_SL_RECORDS     = 2;
    private static final int FN_SL_SCHEMA_URL  = 3;
    private static final int FN_SCOPE_NAME     = 1;
    private static final int FN_SCOPE_VERSION  = 2;
    private static final int FN_RESOURCE_ATTRS = 1;

    private static final String OTEL_SCHEMA_URL = "https://opentelemetry.io/schemas/1.27.0";

    private static final int FN_KV_KEY    = 1;
    private static final int FN_KV_VALUE  = 2;
    private static final int FN_AV_STRING = 1;

    private final OtlpHttpProtobufSender sender;
    private final String serviceName;
    private final AtomicBoolean isShutdown  = new AtomicBoolean(false);
    private final AtomicLong exportedCount  = new AtomicLong(0);
    private final AtomicLong droppedCount   = new AtomicLong(0);
    private final AtomicLong failedBatches  = new AtomicLong(0);

    public OtlpHttpLogExporter() {
        this(resolveServiceName(), OtlpHttpProtobufSender.create());
    }

    public OtlpHttpLogExporter(String serviceName, OtlpHttpProtobufSender sender) {
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    @Override
    public CompletableFuture<Void> export(Collection<LogRecord> logs) {
        if (isShutdown.get() || logs == null || logs.isEmpty()) return CompletableFuture.completedFuture(null);
        byte[] protoBytes;
        try {
            protoBytes = encodeExportRequest(logs, serviceName);
        } catch (Exception e) {
            AgentLogger.warn("OtlpHttpLogExporter: 인코딩 오류 — " + e.getMessage());
            droppedCount.addAndGet(logs.size());
            return CompletableFuture.completedFuture(null);
        }
        int batchSize = logs.size();
        return sender.sendAsync(LOG_PATH, protoBytes).handle((result, ex) -> {
            if (ex != null || result != SendResult.SUCCESS) {
                droppedCount.addAndGet(batchSize);
                failedBatches.incrementAndGet();
            } else {
                exportedCount.addAndGet(batchSize);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        isShutdown.set(true);
        return CompletableFuture.completedFuture(null);
    }

    static byte[] encodeExportRequest(Collection<LogRecord> logs, String serviceName) {
        byte[] resourceBytes = encodeResource(serviceName);
        ByteArrayOutputStream recordsOut = new ByteArrayOutputStream(logs.size() * 200);
        for (LogRecord log : logs) {
            if (log == null) continue;
            ProtoEncoder.writeMessage(recordsOut, FN_SL_RECORDS, encodeLogRecord(log));
        }

        ByteArrayOutputStream scopeOut = new ByteArrayOutputStream(32);
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_NAME, "javi-log");
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_VERSION, "1.0.0");

        ByteArrayOutputStream scopeLogsOut = new ByteArrayOutputStream(64 + recordsOut.size());
        ProtoEncoder.writeMessage(scopeLogsOut, FN_SL_SCOPE, scopeOut.toByteArray());
        byte[] recBytes = recordsOut.toByteArray();
        scopeLogsOut.write(recBytes, 0, recBytes.length);
        ProtoEncoder.writeString(scopeLogsOut, FN_SL_SCHEMA_URL, OTEL_SCHEMA_URL);

        ByteArrayOutputStream rlOut = new ByteArrayOutputStream(128 + scopeLogsOut.size());
        ProtoEncoder.writeMessage(rlOut, FN_RL_RESOURCE, resourceBytes);
        ProtoEncoder.writeMessage(rlOut, FN_RL_SCOPE_LOGS, scopeLogsOut.toByteArray());
        ProtoEncoder.writeString(rlOut, FN_RL_SCHEMA_URL, OTEL_SCHEMA_URL);

        ByteArrayOutputStream requestOut = new ByteArrayOutputStream(rlOut.size() + 4);
        ProtoEncoder.writeMessage(requestOut, FN_RESOURCE_LOGS, rlOut.toByteArray());
        return requestOut.toByteArray();
    }

    private static byte[] encodeResource(String serviceName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        for (Map.Entry<String, String> entry : ResourceInfo.getAttributes().entrySet()) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV(entry.getKey(), entry.getValue()));
        }
        if (!ResourceInfo.getAttributes().containsKey("service.name")) {
            ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("service.name", serviceName));
        }
        return out.toByteArray();
    }

    private static byte[] encodeLogRecord(LogRecord log) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        long timeNs = 0;
        if (log.getTimestamp() != null) {
            timeNs = log.getTimestamp().getEpochSecond() * 1_000_000_000L + log.getTimestamp().getNano();
            ProtoEncoder.writeFixed64Field(out, FN_LOG_TIME_NS, timeNs);
        }
        // observed_time_unix_nano: when the agent intercepted/observed the log.
        // In our inline instrumentation, observation time == emission time.
        long observedNs = timeNs > 0 ? timeNs : System.currentTimeMillis() * 1_000_000L;
        ProtoEncoder.writeFixed64Field(out, FN_LOG_OBSERVED_TIME_NS, observedNs);

        String sev = log.getSeverity() != null ? log.getSeverity().toUpperCase() : "UNSPECIFIED";
        int severityNum = severityToNumber(sev);
        if (severityNum != 0) ProtoEncoder.writeVarint32(out, FN_LOG_SEVERITY_NUM, severityNum);
        ProtoEncoder.writeString(out, FN_LOG_SEVERITY_TEXT, log.getSeverity());

        if (log.getBody() != null) {
            ByteArrayOutputStream bodyAv = new ByteArrayOutputStream(log.getBody().length() + 4);
            ProtoEncoder.writeString(bodyAv, FN_AV_STRING, log.getBody());
            ProtoEncoder.writeMessage(out, FN_LOG_BODY, bodyAv.toByteArray());
        }

        if (log.getLoggerName() != null) ProtoEncoder.writeMessage(out, FN_LOG_ATTRS, encodeStringKV("logger.name", log.getLoggerName()));
        if (log.getAttributes() != null) {
            for (Map.Entry<String, String> entry : log.getAttributes().entrySet()) {
                ProtoEncoder.writeMessage(out, FN_LOG_ATTRS, encodeStringKV(entry.getKey(), entry.getValue()));
            }
        }

        byte[] traceId = ProtoEncoder.hexToBytes(log.getTraceId());
        byte[] spanId  = ProtoEncoder.hexToBytes(log.getSpanId());
        // flags (field 8): W3C trace-flags as fixed32. Set SAMPLED bit (0x01) when trace context is
        // present — signals to backends that the associated trace was sampled and is available.
        if (traceId != null) ProtoEncoder.writeFixed32Field(out, FN_LOG_FLAGS, 0x01);
        if (traceId != null) ProtoEncoder.writeBytes(out, FN_LOG_TRACE_ID, traceId);
        if (spanId  != null) ProtoEncoder.writeBytes(out, FN_LOG_SPAN_ID,  spanId);

        return out.toByteArray();
    }

    private static byte[] encodeStringKV(String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(key.length() + (value != null ? value.length() : 0) + 8);
        ProtoEncoder.writeString(out, FN_KV_KEY, key);
        ByteArrayOutputStream av = new ByteArrayOutputStream((value != null ? value.length() : 0) + 4);
        // writeStringAlways: 빈 문자열도 string_value 타입을 명시해야 AnyValue의 oneof가 올바르게 설정됨
        ProtoEncoder.writeStringAlways(av, FN_AV_STRING, value != null ? value : "");
        ProtoEncoder.writeMessage(out, FN_KV_VALUE, av.toByteArray());
        return out.toByteArray();
    }

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
        return (val != null && !val.isEmpty()) ? val : System.getProperty("javi.service.name", "javi-service");
    }
}

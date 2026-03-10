package com.agent.trace.exporter;

import com.agent.common.grpc.GrpcSender;
import com.agent.common.grpc.GrpcSender.SendResult;
import com.agent.common.grpc.ProtoEncoder;
import com.agent.common.utils.concurrent.CompletableResultCode;
import com.agent.logs.AgentLogger;
import com.agent.span.AttributeKey;
import com.agent.span.ReadableSpan;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanEvent;
import com.agent.span.SpanKind;
import com.agent.span.SpanLink;
import com.agent.span.SpanStatus;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTLP/gRPC 방식으로 Span을 OTel Collector로 전송한다.
 *
 * <p>gRPC 서비스 경로:
 * {@code /opentelemetry.proto.collector.trace.v1.TraceService/Export}
 *
 * <p>인코딩 전략: 두-단계(inner-first) protobuf 바이너리 인코딩.
 * inner message를 먼저 byte[]로 직렬화한 뒤 outer message에 length-delimited로 삽입한다.
 * ByteArrayOutputStream은 export() 호출마다 스택-지역(stack-local)으로 할당하여 스레드 안전성 보장.
 *
 * <p>설정:
 * <ul>
 *   <li>JAVI_GRPC_ENDPOINT / javi.grpc.endpoint (기본: http://localhost:4317)</li>
 *   <li>JAVI_SERVICE_NAME / javi.service.name (기본: javi-service)</li>
 * </ul>
 */
public final class OtlpGrpcSpanExporter implements SpanExporter {

    private static final String GRPC_PATH =
            "/opentelemetry.proto.collector.trace.v1.TraceService/Export";

    // ---- Span proto field numbers (opentelemetry-proto/trace/v1/trace.proto) ----
    private static final int FN_SPAN_TRACE_ID      = 1;  // bytes
    private static final int FN_SPAN_SPAN_ID       = 2;  // bytes
    private static final int FN_SPAN_PARENT_ID     = 4;  // bytes
    private static final int FN_SPAN_NAME          = 5;  // string
    private static final int FN_SPAN_KIND          = 6;  // enum varint
    private static final int FN_SPAN_START_NS      = 7;  // fixed64
    private static final int FN_SPAN_END_NS        = 8;  // fixed64
    private static final int FN_SPAN_ATTRS         = 9;  // repeated KeyValue
    private static final int FN_SPAN_DROPPED_ATTRS = 10; // uint32
    private static final int FN_SPAN_EVENTS        = 11; // repeated Event
    private static final int FN_SPAN_DROPPED_EVTS  = 12; // uint32
    private static final int FN_SPAN_LINKS         = 13; // repeated Link
    private static final int FN_SPAN_STATUS        = 15; // Status

    // Status field numbers
    private static final int FN_STATUS_MESSAGE = 2;
    private static final int FN_STATUS_CODE    = 3;

    // Event field numbers
    private static final int FN_EVENT_TIME_NS = 1;
    private static final int FN_EVENT_NAME    = 2;
    private static final int FN_EVENT_ATTRS   = 3;

    // Link field numbers
    private static final int FN_LINK_TRACE_ID = 1;
    private static final int FN_LINK_SPAN_ID  = 2;

    // Top-level wrapper field numbers
    private static final int FN_RESOURCE_SPANS = 1;  // ExportTraceServiceRequest
    private static final int FN_RS_RESOURCE    = 1;  // ResourceSpans.resource
    private static final int FN_RS_SCOPE_SPANS = 2;  // ResourceSpans.scope_spans
    private static final int FN_SS_SCOPE       = 1;  // ScopeSpans.scope
    private static final int FN_SS_SPANS       = 2;  // ScopeSpans.spans
    private static final int FN_SCOPE_NAME     = 1;  // InstrumentationScope.name
    private static final int FN_SCOPE_VERSION  = 2;  // InstrumentationScope.version
    private static final int FN_RESOURCE_ATTRS = 1;  // Resource.attributes

    // KeyValue / AnyValue field numbers
    private static final int FN_KV_KEY      = 1;
    private static final int FN_KV_VALUE    = 2;
    private static final int FN_AV_STRING   = 1;
    private static final int FN_AV_BOOL     = 2;
    private static final int FN_AV_INT      = 3;
    private static final int FN_AV_DOUBLE   = 4;

    // ---- 내부 상태 ----
    private final GrpcSender sender;
    private final String serviceName;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicLong exportedSpans = new AtomicLong(0);
    private final AtomicLong droppedSpans  = new AtomicLong(0);
    private final AtomicLong failedBatches = new AtomicLong(0);

    // ---- 생성자 ----

    public OtlpGrpcSpanExporter() {
        this(resolveServiceName(), GrpcSender.create());
    }

    public OtlpGrpcSpanExporter(String serviceName, GrpcSender sender) {
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    // ---- SpanExporter 구현 ----

    @Override
    public CompletableResultCode export(Collection<Span> spans) {
        if (isShutdown.get() || spans == null || spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            byte[] protoBytes = encodeExportRequest(spans, serviceName);
            SendResult result = sender.send(GRPC_PATH, protoBytes);

            if (result == SendResult.SUCCESS) {
                exportedSpans.addAndGet(spans.size());
                AgentLogger.debug("OtlpGrpcSpanExporter: export 성공 spans=" + spans.size());
                return CompletableResultCode.ofSuccess();
            }

            droppedSpans.addAndGet(spans.size());
            failedBatches.incrementAndGet();
            AgentLogger.warn("OtlpGrpcSpanExporter: 배치 전송 실패 spans=" + spans.size()
                    + " dropped_total=" + droppedSpans.get());
            return CompletableResultCode.ofFailure();
        } catch (Exception e) {
            AgentLogger.warn("OtlpGrpcSpanExporter: 인코딩 오류 — " + e.getMessage());
            droppedSpans.addAndGet(spans.size());
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            AgentLogger.info("OtlpGrpcSpanExporter shutdown: exported=" + exportedSpans.get()
                    + " dropped=" + droppedSpans.get()
                    + " failed_batches=" + failedBatches.get());
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public void close() {
        shutdown().join(10, TimeUnit.SECONDS);
    }

    // ---- Protobuf 인코딩 ----

    static byte[] encodeExportRequest(Collection<Span> spans, String serviceName) {
        // 1) Resource
        byte[] resourceBytes = encodeResource(serviceName);

        // 2) 각 Span
        ByteArrayOutputStream spansOut = new ByteArrayOutputStream(spans.size() * 300);
        for (Span span : spans) {
            if (!(span instanceof ReadableSpan)) continue;
            byte[] spanBytes = encodeSpan((ReadableSpan) span);
            ProtoEncoder.writeMessage(spansOut, FN_SS_SPANS, spanBytes);
        }

        // 3) InstrumentationScope
        ByteArrayOutputStream scopeOut = new ByteArrayOutputStream(32);
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_NAME, "javi-agent");
        ProtoEncoder.writeString(scopeOut, FN_SCOPE_VERSION, "1.0.0");

        // 4) ScopeSpans
        ByteArrayOutputStream scopeSpansOut = new ByteArrayOutputStream(64 + spansOut.size());
        ProtoEncoder.writeMessage(scopeSpansOut, FN_SS_SCOPE, scopeOut.toByteArray());
        byte[] spansBytes = spansOut.toByteArray();
        scopeSpansOut.write(spansBytes, 0, spansBytes.length);

        // 5) ResourceSpans
        ByteArrayOutputStream rsOut = new ByteArrayOutputStream(128 + scopeSpansOut.size());
        ProtoEncoder.writeMessage(rsOut, FN_RS_RESOURCE, resourceBytes);
        ProtoEncoder.writeMessage(rsOut, FN_RS_SCOPE_SPANS, scopeSpansOut.toByteArray());

        // 6) ExportTraceServiceRequest
        ByteArrayOutputStream requestOut = new ByteArrayOutputStream(rsOut.size() + 4);
        ProtoEncoder.writeMessage(requestOut, FN_RESOURCE_SPANS, rsOut.toByteArray());
        return requestOut.toByteArray();
    }

    private static byte[] encodeResource(String serviceName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("service.name", serviceName));
        ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("telemetry.sdk.name", "javi-agent"));
        ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("telemetry.sdk.language", "java"));
        return out.toByteArray();
    }

    private static byte[] encodeSpan(ReadableSpan rs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        SpanContext ctx = rs.getContext();

        byte[] traceId = ProtoEncoder.hexToBytes(ctx.getTraceId());
        byte[] spanId  = ProtoEncoder.hexToBytes(ctx.getSpanId());
        if (traceId != null) ProtoEncoder.writeBytes(out, FN_SPAN_TRACE_ID, traceId);
        if (spanId  != null) ProtoEncoder.writeBytes(out, FN_SPAN_SPAN_ID,  spanId);

        byte[] parentId = ProtoEncoder.hexToBytes(ctx.getParentSpanId());
        if (parentId != null) ProtoEncoder.writeBytes(out, FN_SPAN_PARENT_ID, parentId);

        ProtoEncoder.writeString(out, FN_SPAN_NAME, rs.getName());
        ProtoEncoder.writeVarint32(out, FN_SPAN_KIND, kindToOtlp(rs.getKind()));
        ProtoEncoder.writeFixed64Field(out, FN_SPAN_START_NS, rs.getStartTimeNanos());
        ProtoEncoder.writeFixed64Field(out, FN_SPAN_END_NS,   rs.getEndTimeNanos());

        Set<String> dropKeys = com.agent.config.RemoteConfigHolder.get().getSpanDrop();
        if (rs.getAttributes() != null) {
            for (Map.Entry<AttributeKey<?>, Object> entry : rs.getAttributes().entrySet()) {
                String key = entry.getKey().getKey();
                if (!dropKeys.isEmpty() && dropKeys.contains(key)) continue;
                byte[] kv = encodeAnyKV(key, entry.getValue());
                if (kv != null) ProtoEncoder.writeMessage(out, FN_SPAN_ATTRS, kv);
            }
        }

        int droppedAttrs = rs.getDroppedAttributeCount();
        if (droppedAttrs > 0) ProtoEncoder.writeVarint32(out, FN_SPAN_DROPPED_ATTRS, droppedAttrs);

        if (rs.getEvents() != null) {
            for (SpanEvent event : rs.getEvents()) {
                ProtoEncoder.writeMessage(out, FN_SPAN_EVENTS, encodeEvent(event));
            }
        }
        int droppedEvts = rs.getDroppedEventCount();
        if (droppedEvts > 0) ProtoEncoder.writeVarint32(out, FN_SPAN_DROPPED_EVTS, droppedEvts);

        if (rs.getLinks() != null) {
            for (SpanLink link : rs.getLinks()) {
                ProtoEncoder.writeMessage(out, FN_SPAN_LINKS, encodeLink(link));
            }
        }

        byte[] statusBytes = encodeStatus(rs.getStatus(), rs.getStatusDescription());
        if (statusBytes.length > 0) ProtoEncoder.writeMessage(out, FN_SPAN_STATUS, statusBytes);

        return out.toByteArray();
    }

    private static byte[] encodeEvent(SpanEvent event) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        ProtoEncoder.writeFixed64Field(out, FN_EVENT_TIME_NS, event.getTimestampNanos());
        ProtoEncoder.writeString(out, FN_EVENT_NAME, event.getName());
        if (event.getAttributes() != null) {
            for (Map.Entry<AttributeKey<?>, Object> e : event.getAttributes().entrySet()) {
                byte[] kv = encodeAnyKV(e.getKey().getKey(), e.getValue());
                if (kv != null) ProtoEncoder.writeMessage(out, FN_EVENT_ATTRS, kv);
            }
        }
        return out.toByteArray();
    }

    private static byte[] encodeLink(SpanLink link) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        SpanContext ctx = link.getSpanContext();
        byte[] traceId = ProtoEncoder.hexToBytes(ctx.getTraceId());
        byte[] spanId  = ProtoEncoder.hexToBytes(ctx.getSpanId());
        if (traceId != null) ProtoEncoder.writeBytes(out, FN_LINK_TRACE_ID, traceId);
        if (spanId  != null) ProtoEncoder.writeBytes(out, FN_LINK_SPAN_ID,  spanId);
        return out.toByteArray();
    }

    private static byte[] encodeStatus(SpanStatus status, String description) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        int code = 0;
        if (status == SpanStatus.OK)    code = 1;
        if (status == SpanStatus.ERROR) code = 2;
        if (code != 0) ProtoEncoder.writeVarint32(out, FN_STATUS_CODE, code);
        if (description != null && !description.isEmpty()) {
            ProtoEncoder.writeString(out, FN_STATUS_MESSAGE, description);
        }
        return out.toByteArray();
    }

    private static byte[] encodeStringKV(String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(key.length() + value.length() + 8);
        ProtoEncoder.writeString(out, FN_KV_KEY, key);
        ByteArrayOutputStream av = new ByteArrayOutputStream(value.length() + 4);
        ProtoEncoder.writeString(av, FN_AV_STRING, value);
        ProtoEncoder.writeMessage(out, FN_KV_VALUE, av.toByteArray());
        return out.toByteArray();
    }

    private static byte[] encodeAnyKV(String key, Object value) {
        ByteArrayOutputStream avOut = new ByteArrayOutputStream(32);
        if (value instanceof String) {
            ProtoEncoder.writeString(avOut, FN_AV_STRING, (String) value);
        } else if (value instanceof Long || value instanceof Integer) {
            long v = ((Number) value).longValue();
            ProtoEncoder.writeTag(avOut, FN_AV_INT, ProtoEncoder.WIRE_VARINT);
            ProtoEncoder.writeRawVarint64(avOut, v);
        } else if (value instanceof Double || value instanceof Float) {
            ProtoEncoder.writeDoubleField(avOut, FN_AV_DOUBLE, ((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            ProtoEncoder.writeVarint32(avOut, FN_AV_BOOL, (Boolean) value ? 1 : 0);
        } else if (value != null) {
            ProtoEncoder.writeString(avOut, FN_AV_STRING, value.toString());
        } else {
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(key.length() + avOut.size() + 8);
        ProtoEncoder.writeString(out, FN_KV_KEY, key);
        ProtoEncoder.writeMessage(out, FN_KV_VALUE, avOut.toByteArray());
        return out.toByteArray();
    }

    private static int kindToOtlp(SpanKind kind) {
        if (kind == null) return 1;
        switch (kind) {
            case SERVER:   return 2;
            case CLIENT:   return 3;
            case PRODUCER: return 4;
            case CONSUMER: return 5;
            default:       return 1;
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

package com.agent.trace.exporter;

import com.agent.common.OtlpHttpProtobufSender;
import com.agent.common.OtlpHttpProtobufSender.SendResult;
import com.agent.common.ProtoEncoder;
import com.agent.common.ResourceInfo;
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
import com.agent.trace.InstrumentationScopeInfo;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTLP/HTTP Protobuf 방식으로 Span을 OTel Collector로 전송한다.
 */
public final class OtlpHttpSpanExporter implements SpanExporter {

    private static final String TRACE_PATH = "/v1/traces";

    // ---- Span proto field numbers ----
    private static final int FN_SPAN_TRACE_ID      = 1;
    private static final int FN_SPAN_SPAN_ID       = 2;
    private static final int FN_SPAN_TRACE_STATE   = 3;
    private static final int FN_SPAN_PARENT_ID     = 4;
    private static final int FN_SPAN_NAME          = 5;
    private static final int FN_SPAN_KIND          = 6;
    private static final int FN_SPAN_START_NS      = 7;
    private static final int FN_SPAN_END_NS        = 8;
    private static final int FN_SPAN_ATTRS         = 9;
    private static final int FN_SPAN_DROPPED_ATTRS = 10;
    private static final int FN_SPAN_EVENTS        = 11;
    private static final int FN_SPAN_DROPPED_EVTS  = 12;
    private static final int FN_SPAN_LINKS         = 13;
    private static final int FN_SPAN_DROPPED_LINKS = 14;
    private static final int FN_SPAN_STATUS        = 15;
    private static final int FN_SPAN_FLAGS         = 16;

    private static final int FN_STATUS_MESSAGE = 2;
    private static final int FN_STATUS_CODE    = 3;
    private static final int FN_EVENT_TIME_NS = 1;
    private static final int FN_EVENT_NAME    = 2;
    private static final int FN_EVENT_ATTRS   = 3;
    private static final int FN_LINK_TRACE_ID = 1;
    private static final int FN_LINK_SPAN_ID  = 2;
    private static final int FN_LINK_ATTRS    = 3;
    private static final int FN_RESOURCE_SPANS = 1;
    private static final int FN_RS_RESOURCE    = 1;
    private static final int FN_RS_SCOPE_SPANS = 2;
    private static final int FN_SS_SCOPE       = 1;
    private static final int FN_SS_SPANS       = 2;
    private static final int FN_SCOPE_NAME     = 1;
    private static final int FN_SCOPE_VERSION  = 2;
    private static final int FN_RESOURCE_ATTRS = 1;
    private static final int FN_KV_KEY      = 1;
    private static final int FN_KV_VALUE    = 2;
    private static final int FN_AV_STRING   = 1;
    private static final int FN_AV_BOOL     = 2;
    private static final int FN_AV_INT      = 3;
    private static final int FN_AV_DOUBLE   = 4;

    private static final InstrumentationScopeInfo DEFAULT_SCOPE =
            new InstrumentationScopeInfo("javi-agent", "1.0.0", null);

    // P2: ThreadLocal BAOS 풀 — 배치당 수천 개의 단명 BAOS 객체 생성을 방지해 GC 압력을 줄인다.
    private static final ThreadLocal<ArrayDeque<ByteArrayOutputStream>> TL_BAOS_POOL =
            ThreadLocal.withInitial(ArrayDeque::new);

    private final OtlpHttpProtobufSender sender;
    private final String serviceName;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicLong exportedSpans = new AtomicLong(0);
    private final AtomicLong droppedSpans  = new AtomicLong(0);
    private volatile byte[] cachedResourceBytes;

    public OtlpHttpSpanExporter() {
        this(resolveServiceName(), OtlpHttpProtobufSender.create());
    }

    public OtlpHttpSpanExporter(String serviceName, OtlpHttpProtobufSender sender) {
        this.serviceName = serviceName;
        this.sender      = sender;
    }

    @Override
    public CompletableResultCode export(Collection<Span> spans) {
        if (isShutdown.get() || spans == null || spans.isEmpty()) return CompletableResultCode.ofSuccess();
        try {
            byte[] protoBytes = encodeExportRequestWithResource(spans, getResourceBytes());
            SendResult result = sender.send(TRACE_PATH, protoBytes);

            if (result == SendResult.SUCCESS) {
                exportedSpans.addAndGet(spans.size());
                return CompletableResultCode.ofSuccess();
            }
            droppedSpans.addAndGet(spans.size());
            return CompletableResultCode.ofFailure();
        } catch (Exception e) {
            AgentLogger.warn("OtlpHttpSpanExporter: 인코딩 오류 — " + e.getMessage());
            droppedSpans.addAndGet(spans.size());
            return CompletableResultCode.ofFailure();
        }
    }

    // P1b: 비동기 export — BatchSpanProcessor Worker를 블로킹하지 않는다.
    @Override
    public CompletableFuture<CompletableResultCode> exportAsync(Collection<Span> spans) {
        if (isShutdown.get() || spans == null || spans.isEmpty())
            return CompletableFuture.completedFuture(CompletableResultCode.ofSuccess());
        final int count = spans.size();
        try {
            byte[] protoBytes = encodeExportRequestWithResource(spans, getResourceBytes());
            return sender.sendAsync(TRACE_PATH, protoBytes).thenApply(result -> {
                if (result == SendResult.SUCCESS) {
                    exportedSpans.addAndGet(count);
                    return CompletableResultCode.ofSuccess();
                }
                droppedSpans.addAndGet(count);
                return CompletableResultCode.ofFailure();
            });
        } catch (Exception e) {
            AgentLogger.warn("OtlpHttpSpanExporter: 인코딩 오류 — " + e.getMessage());
            droppedSpans.addAndGet(count);
            return CompletableFuture.completedFuture(CompletableResultCode.ofFailure());
        }
    }

    @Override
    public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

    @Override
    public CompletableResultCode shutdown() {
        isShutdown.set(true);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public void close() { shutdown().join(10, TimeUnit.SECONDS); }

    private byte[] getResourceBytes() {
        if (cachedResourceBytes == null) {
            synchronized (this) {
                if (cachedResourceBytes == null) cachedResourceBytes = encodeResource(serviceName);
            }
        }
        return cachedResourceBytes;
    }

    // P2: BAOS 풀 헬퍼 — 풀이 비어 있으면 새로 할당, 있으면 재사용
    private static ByteArrayOutputStream acquire(int hint) {
        ArrayDeque<ByteArrayOutputStream> pool = TL_BAOS_POOL.get();
        ByteArrayOutputStream baos = pool.isEmpty() ? new ByteArrayOutputStream(hint) : pool.pop();
        baos.reset();
        return baos;
    }

    private static void release(ByteArrayOutputStream baos) {
        TL_BAOS_POOL.get().push(baos);
    }

    private byte[] encodeExportRequestWithResource(Collection<Span> spans, byte[] resourceBytes) {
        Set<String> dropKeys = com.agent.config.RemoteConfigHolder.get().getSpanDrop();
        Map<InstrumentationScopeInfo, List<ReadableSpan>> byScope = new LinkedHashMap<>();
        for (Span span : spans) {
            if (!(span instanceof ReadableSpan)) continue;
            ReadableSpan rs = (ReadableSpan) span;
            InstrumentationScopeInfo scope = rs.getInstrumentationScopeInfo();
            if (scope == null) scope = DEFAULT_SCOPE;
            byScope.computeIfAbsent(scope, k -> new ArrayList<>()).add(rs);
        }

        ByteArrayOutputStream rsOut = acquire(128 + spans.size() * 300);
        try {
            ProtoEncoder.writeMessage(rsOut, FN_RS_RESOURCE, resourceBytes);

            for (Map.Entry<InstrumentationScopeInfo, List<ReadableSpan>> entry : byScope.entrySet()) {
                InstrumentationScopeInfo scope = entry.getKey();
                List<ReadableSpan> scopeSpanList = entry.getValue();

                ByteArrayOutputStream spansOut = acquire(scopeSpanList.size() * 300);
                try {
                    for (ReadableSpan rs : scopeSpanList) {
                        ProtoEncoder.writeMessage(spansOut, FN_SS_SPANS, encodeSpan(rs, dropKeys));
                    }

                    ByteArrayOutputStream scopeOut = acquire(64);
                    try {
                        ProtoEncoder.writeString(scopeOut, FN_SCOPE_NAME,
                                scope.getName() != null ? scope.getName() : "javi-agent");
                        if (scope.getVersion() != null)
                            ProtoEncoder.writeString(scopeOut, FN_SCOPE_VERSION, scope.getVersion());

                        ByteArrayOutputStream scopeSpansOut = acquire(64 + spansOut.size());
                        try {
                            ProtoEncoder.writeMessage(scopeSpansOut, FN_SS_SCOPE, scopeOut.toByteArray());
                            byte[] spansBytes = spansOut.toByteArray();
                            scopeSpansOut.write(spansBytes, 0, spansBytes.length);
                            ProtoEncoder.writeMessage(rsOut, FN_RS_SCOPE_SPANS, scopeSpansOut.toByteArray());
                        } finally {
                            release(scopeSpansOut);
                        }
                    } finally {
                        release(scopeOut);
                    }
                } finally {
                    release(spansOut);
                }
            }

            ByteArrayOutputStream requestOut = acquire(rsOut.size() + 4);
            try {
                ProtoEncoder.writeMessage(requestOut, FN_RESOURCE_SPANS, rsOut.toByteArray());
                return requestOut.toByteArray();
            } finally {
                release(requestOut);
            }
        } finally {
            release(rsOut);
        }
    }

    private static byte[] encodeResource(String serviceName) {
        ByteArrayOutputStream out = acquire(256);
        try {
            for (Map.Entry<String, String> entry : ResourceInfo.getAttributes().entrySet()) {
                ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV(entry.getKey(), entry.getValue()));
            }
            if (!ResourceInfo.getAttributes().containsKey("service.name")) {
                ProtoEncoder.writeMessage(out, FN_RESOURCE_ATTRS, encodeStringKV("service.name", serviceName));
            }
            return out.toByteArray();
        } finally {
            release(out);
        }
    }

    private static byte[] encodeSpan(ReadableSpan rs, Set<String> dropKeys) {
        ByteArrayOutputStream out = acquire(256);
        try {
            SpanContext ctx = rs.getContext();

            byte[] traceId = ProtoEncoder.hexToBytes(ctx.getTraceId());
            byte[] spanId  = ProtoEncoder.hexToBytes(ctx.getSpanId());
            if (traceId != null) ProtoEncoder.writeBytes(out, FN_SPAN_TRACE_ID, traceId);
            if (spanId  != null) ProtoEncoder.writeBytes(out, FN_SPAN_SPAN_ID,  spanId);

            String traceState = ctx.getTraceState().getValue();
            if (traceState != null && !traceState.isEmpty())
                ProtoEncoder.writeString(out, FN_SPAN_TRACE_STATE, traceState);

            byte[] parentId = ProtoEncoder.hexToBytes(ctx.getParentSpanId());
            if (parentId != null) ProtoEncoder.writeBytes(out, FN_SPAN_PARENT_ID, parentId);

            ProtoEncoder.writeString(out, FN_SPAN_NAME, rs.getName());
            ProtoEncoder.writeVarint32(out, FN_SPAN_KIND, kindToOtlp(rs.getKind()));
            ProtoEncoder.writeFixed64Field(out, FN_SPAN_START_NS, rs.getStartTimeNanos());
            ProtoEncoder.writeFixed64Field(out, FN_SPAN_END_NS,   rs.getEndTimeNanos());

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

            if (rs.getLinks() != null) {
                for (SpanLink link : rs.getLinks()) {
                    if (link != null) ProtoEncoder.writeMessage(out, FN_SPAN_LINKS, encodeLink(link));
                }
            }

            int droppedLinks = rs.getDroppedLinksCount();
            if (droppedLinks > 0) ProtoEncoder.writeVarint32(out, FN_SPAN_DROPPED_LINKS, droppedLinks);

            byte[] statusBytes = encodeStatus(rs.getStatus(), rs.getStatusDescription());
            if (statusBytes.length > 0) ProtoEncoder.writeMessage(out, FN_SPAN_STATUS, statusBytes);

            int flags = ctx.getTraceFlags().asByte() & 0xFF;
            if (flags != 0) ProtoEncoder.writeFixed32Field(out, FN_SPAN_FLAGS, flags);

            return out.toByteArray();
        } finally {
            release(out);
        }
    }

    private static byte[] encodeEvent(SpanEvent event) {
        ByteArrayOutputStream out = acquire(64);
        try {
            ProtoEncoder.writeFixed64Field(out, FN_EVENT_TIME_NS, event.getTimestampNanos());
            ProtoEncoder.writeString(out, FN_EVENT_NAME, event.getName());
            if (event.getAttributes() != null) {
                for (Map.Entry<AttributeKey<?>, Object> e : event.getAttributes().entrySet()) {
                    byte[] kv = encodeAnyKV(e.getKey().getKey(), e.getValue());
                    if (kv != null) ProtoEncoder.writeMessage(out, FN_EVENT_ATTRS, kv);
                }
            }
            return out.toByteArray();
        } finally {
            release(out);
        }
    }

    private static byte[] encodeLink(SpanLink link) {
        ByteArrayOutputStream out = acquire(64);
        try {
            SpanContext lCtx = link.getSpanContext();
            if (lCtx != null) {
                byte[] ltid = ProtoEncoder.hexToBytes(lCtx.getTraceId());
                byte[] lsid = ProtoEncoder.hexToBytes(lCtx.getSpanId());
                if (ltid != null) ProtoEncoder.writeBytes(out, FN_LINK_TRACE_ID, ltid);
                if (lsid != null) ProtoEncoder.writeBytes(out, FN_LINK_SPAN_ID,  lsid);
                String lts = lCtx.getTraceState().getValue();
                if (lts != null && !lts.isEmpty()) ProtoEncoder.writeString(out, 3, lts);
            }
            if (link.getAttributes() != null) {
                for (Map.Entry<AttributeKey<?>, Object> e : link.getAttributes().entrySet()) {
                    byte[] kv = encodeAnyKV(e.getKey().getKey(), e.getValue());
                    if (kv != null) ProtoEncoder.writeMessage(out, FN_LINK_ATTRS, kv);
                }
            }
            return out.toByteArray();
        } finally {
            release(out);
        }
    }

    private static byte[] encodeStatus(SpanStatus status, String description) {
        ByteArrayOutputStream out = acquire(16);
        try {
            int code = (status == SpanStatus.OK) ? 1 : (status == SpanStatus.ERROR) ? 2 : 0;
            if (code != 0) ProtoEncoder.writeVarint32(out, FN_STATUS_CODE, code);
            if (description != null && !description.isEmpty())
                ProtoEncoder.writeString(out, FN_STATUS_MESSAGE, description);
            return out.toByteArray();
        } finally {
            release(out);
        }
    }

    private static byte[] encodeStringKV(String key, String value) {
        ByteArrayOutputStream av = acquire(value.length() + 4);
        try {
            ProtoEncoder.writeString(av, FN_AV_STRING, value);
            ByteArrayOutputStream out = acquire(key.length() + value.length() + 8);
            try {
                ProtoEncoder.writeString(out, FN_KV_KEY, key);
                ProtoEncoder.writeMessage(out, FN_KV_VALUE, av.toByteArray());
                return out.toByteArray();
            } finally {
                release(out);
            }
        } finally {
            release(av);
        }
    }

    private static byte[] encodeAnyKV(String key, Object value) {
        ByteArrayOutputStream avOut = acquire(32);
        try {
            if (value instanceof String) {
                ProtoEncoder.writeString(avOut, FN_AV_STRING, (String) value);
            } else if (value instanceof Long || value instanceof Integer) {
                ProtoEncoder.writeTag(avOut, FN_AV_INT, ProtoEncoder.WIRE_VARINT);
                ProtoEncoder.writeRawVarint64(avOut, ((Number) value).longValue());
            } else if (value instanceof Double || value instanceof Float) {
                ProtoEncoder.writeDoubleField(avOut, FN_AV_DOUBLE, ((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                ProtoEncoder.writeVarint32(avOut, FN_AV_BOOL, (Boolean) value ? 1 : 0);
            } else if (value != null) {
                ProtoEncoder.writeString(avOut, FN_AV_STRING, value.toString());
            } else {
                return null;
            }

            ByteArrayOutputStream out = acquire(key.length() + avOut.size() + 8);
            try {
                ProtoEncoder.writeString(out, FN_KV_KEY, key);
                ProtoEncoder.writeMessage(out, FN_KV_VALUE, avOut.toByteArray());
                return out.toByteArray();
            } finally {
                release(out);
            }
        } finally {
            release(avOut);
        }
    }

    private static int kindToOtlp(SpanKind kind) {
        if (kind == null) return 1;
        switch (kind) {
            case SERVER: return 2;
            case CLIENT: return 3;
            case PRODUCER: return 4;
            case CONSUMER: return 5;
            default: return 1;
        }
    }

    private static String resolveServiceName() {
        String val = System.getenv("JAVI_SERVICE_NAME");
        return (val != null && !val.isEmpty()) ? val : System.getProperty("javi.service.name", "javi-service");
    }
}

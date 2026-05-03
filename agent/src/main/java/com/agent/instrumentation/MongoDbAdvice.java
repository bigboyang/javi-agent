package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Context;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;

/**
 * MongoDB 드라이버 계측.
 *
 * 대상: com.mongodb.internal.connection.InternalStreamConnection.sendAndReceive(CommandMessage, ...)
 *
 * 선택 근거 및 트레이드오프 비교:
 *
 * | 후보 진입점                                    | 범용성 | 정보 접근성 | 비고                         |
 * |-----------------------------------------------|--------|-------------|------------------------------|
 * | MongoCollection.find/insertOne 등 (고수준)     | 낮음   | 높음        | 수십 개 메서드 개별 계측 필요 |
 * | MixedBulkWriteOperation / FindOperation.execute| 중간   | 중간        | 동기 드라이버 전용            |
 * | CommandOperationHelper.executeCommand          | 중간   | 높음        | 버전마다 시그니처 변동        |
 * | InternalStreamConnection.sendAndReceive        | 높음   | 중간        | 모든 명령 통과, 동기/비동기   |
 * | DefaultConnectionPool.get (연결 획득)          | 높음   | 낮음        | 명령 정보 없음                |
 *
 * InternalStreamConnection.sendAndReceive를 선택한 이유:
 * - 동기 드라이버의 모든 CommandMessage가 이 메서드를 통과한다.
 * - CommandMessage에서 commandDocument(BsonDocument)를 추출해 명령/컬렉션 이름을 파싱 가능.
 * - MongoDB 4.x ~ 6.x 드라이버에서 안정적으로 존재한다.
 *
 * Reactive(mongo-driver-reactivestreams)의 경우:
 * - sendAndReceiveAsync()가 대응 진입점이나 이 Advice는 동기 버전만 처리한다.
 * - 비동기 버전은 별도 AsyncMongoDbAdvice가 필요하다 (현재 미구현).
 *
 * 컬렉션/명령 추출:
 * - CommandMessage.getCommandDocument() -> BsonDocument
 * - BsonDocument.getFirstKey() -> 명령어 이름 (find, insert, update, delete, aggregate 등)
 * - BsonDocument.getString(commandName).getValue() -> 컬렉션 이름
 *
 * Span 명: "mongodb collectionName.operation" (예: "mongodb users.find")
 */
public final class MongoDbAdvice {

    // CommandMessage에서 BsonDocument를 꺼내는 reflection 캐시
    public static volatile Method GET_COMMAND_DOCUMENT = null;
    public static volatile Method GET_FIRST_KEY = null;
    public static volatile Method BSON_GET_STRING = null;
    public static volatile Method BSON_STRING_GET_VALUE = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.Argument(0) Object commandMessage) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;

        if (commandMessage == null) return null;

        String dbOperation = "command";
        String collectionName = "unknown";

        try {
            // CommandMessage -> BsonDocument (commandDocument)
            Object bsonDoc = extractCommandDocument(commandMessage);
            if (bsonDoc != null) {
                // BsonDocument.getFirstKey() -> 명령어 이름 (예: "find", "insert")
                String cmdKey = extractFirstKey(bsonDoc);
                if (cmdKey != null) {
                    dbOperation = cmdKey.toLowerCase();
                    // 명령어 이름으로 컬렉션 이름 조회: document.getString("find").getValue()
                    String col = extractStringValue(bsonDoc, cmdKey);
                    if (col != null && !col.isEmpty()) {
                        collectionName = col;
                    }
                }
            }
        } catch (Throwable ignored) {}

        String spanName = "mongodb " + collectionName + "." + dbOperation;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.mongodb");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        // OTel DB 시맨틱 속성 — https://opentelemetry.io/docs/specs/semconv/database/
        span.setAttribute("db.system", "mongodb");
        span.setAttribute("db.operation.name", dbOperation);
        span.setAttribute("db.collection.name", collectionName);
        span.setAttribute("peer.service", "mongodb");

        // db.namespace 추출 시도 (CommandMessage.getNamespace().getDatabaseName())
        try {
            Object namespace = commandMessage.getClass().getMethod("getNamespace").invoke(commandMessage);
            if (namespace != null) {
                String dbName = (String) namespace.getClass().getMethod("getDatabaseName").invoke(namespace);
                if (dbName != null) span.setAttribute("db.namespace", dbName);
            }
        } catch (Throwable ignored) {}

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        AgentLogger.debug("[MongoDB] span started: " + spanName);
        return new State(span, scope, prevTraceId, prevSpanId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Thrown Throwable error) {

        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();

        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId == null) MDC.remove("spanId");
        else MDC.put("spanId", state.prevSpanId);
    }

    // --- reflection 헬퍼 ---

    public static Object extractCommandDocument(Object commandMessage) {
        try {
            Method m = GET_COMMAND_DOCUMENT;
            if (m == null) {
                m = commandMessage.getClass().getMethod("getCommandDocument");
                GET_COMMAND_DOCUMENT = m;
            }
            return m.invoke(commandMessage);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String extractFirstKey(Object bsonDoc) {
        try {
            Method m = GET_FIRST_KEY;
            if (m == null) {
                m = bsonDoc.getClass().getMethod("getFirstKey");
                GET_FIRST_KEY = m;
            }
            return (String) m.invoke(bsonDoc);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * BsonDocument.getString(key).getValue() 로 컬렉션 이름을 추출한다.
     * BsonDocument.getString()은 BsonString을 반환한다.
     */
    public static String extractStringValue(Object bsonDoc, String key) {
        try {
            Method getStr = BSON_GET_STRING;
            if (getStr == null) {
                getStr = bsonDoc.getClass().getMethod("getString", String.class);
                BSON_GET_STRING = getStr;
            }
            Object bsonStr = getStr.invoke(bsonDoc, key);
            if (bsonStr == null) return null;

            Method getValue = BSON_STRING_GET_VALUE;
            if (getValue == null) {
                getValue = bsonStr.getClass().getMethod("getValue");
                BSON_STRING_GET_VALUE = getValue;
            }
            return (String) getValue.invoke(bsonStr);
        } catch (Throwable t) {
            return null;
        }
    }

    public static final class State {
        public final Span span;
        public final Scope scope;
        public final String prevTraceId;
        public final String prevSpanId;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId) {
            this.span = span;
            this.scope = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId = prevSpanId;
        }
    }

    private MongoDbAdvice() {}
}

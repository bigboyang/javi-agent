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

/**
 * Jedis Redis 클라이언트 계측.
 *
 * 대상:
 *   - Jedis 4.x: redis.clients.jedis.Connection.executeCommand(CommandArguments)
 *                → sendCommand + readResponse를 하나의 메서드로 포함 (완전한 round-trip 측정)
 *   - Jedis 3.x: redis.clients.jedis.Connection.sendCommand(ProtocolCommand, byte[]...)
 *                → 명령 전송 단계만 계측 (응답 수신은 별도 호출이므로 타이밍이 근사값)
 *
 * 명령 이름 추출 전략 (extractCommandName):
 *   1. ProtocolCommand.name()    — Jedis 3.x Protocol.Command enum
 *   2. CommandArguments.getProtocolCommand().name()  — Jedis 4.x
 *   3. toString() 첫 단어 폴백
 *
 * 한계:
 *   Jedis 3.x의 Pipeline 모드에서는 sendCommand가 응답 수신 전에 연속 호출될 수 있어
 *   개별 명령의 latency 측정이 부정확할 수 있다.
 */
public final class JedisAdvice {

    // Jedis 3.x: ProtocolCommand(enum).name()
    private static volatile java.lang.reflect.Method ENUM_NAME_METHOD = null;
    // Jedis 4.x: CommandArguments.getProtocolCommand()
    private static volatile java.lang.reflect.Method GET_PROTOCOL_CMD_METHOD = null;
    // Jedis 4.x: ProtocolCommand(inner).name() — 3.x와 별도 캐싱
    private static volatile java.lang.reflect.Method PROTO_CMD_NAME_METHOD = null;
    // 피어 정보: Jedis 3.x
    private static volatile java.lang.reflect.Method GET_HOST_METHOD = null;
    private static volatile java.lang.reflect.Method GET_PORT_METHOD = null;
    // 피어 정보: Jedis 4.x
    private static volatile java.lang.reflect.Method GET_HAP_METHOD = null;
    private static volatile java.lang.reflect.Method HAP_GET_HOST_METHOD = null;
    private static volatile java.lang.reflect.Method HAP_GET_PORT_METHOD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.This Object connection,
                                @Advice.Argument(0) Object commandArg) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;

        String commandName = extractCommandName(commandArg);
        String spanName = "REDIS " + commandName;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.redis.jedis");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "redis");
        span.setAttribute("db.operation.name", commandName);

        // host/port 추출 (Connection 객체에서)
        extractPeerInfo(connection, span);

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        AgentLogger.debug("[Jedis] span started: " + spanName);
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

    /**
     * Jedis 3.x ProtocolCommand(enum) 및 4.x CommandArguments 모두 처리.
     * 각 Method는 첫 호출 시 캐싱 (DCL 패턴).
     */
    static String extractCommandName(Object commandArg) {
        if (commandArg == null) return "UNKNOWN";

        // Jedis 3.x: ProtocolCommand는 Enum → name() 메서드 사용
        java.lang.reflect.Method nameMethod = ENUM_NAME_METHOD;
        if (nameMethod == null) {
            try {
                nameMethod = commandArg.getClass().getMethod("name");
                ENUM_NAME_METHOD = nameMethod;
            } catch (Throwable ignored) {}
        }
        if (nameMethod != null) {
            try {
                Object result = nameMethod.invoke(commandArg);
                if (result instanceof String) return (String) result;
            } catch (Throwable ignored) {}
        }

        // Jedis 4.x: CommandArguments.getProtocolCommand().name()
        java.lang.reflect.Method getProtoCmdMethod = GET_PROTOCOL_CMD_METHOD;
        if (getProtoCmdMethod == null) {
            try {
                getProtoCmdMethod = commandArg.getClass().getMethod("getProtocolCommand");
                GET_PROTOCOL_CMD_METHOD = getProtoCmdMethod;
            } catch (Throwable ignored) {}
        }
        if (getProtoCmdMethod != null) {
            try {
                Object protocolCmd = getProtoCmdMethod.invoke(commandArg);
                if (protocolCmd != null) {
                    java.lang.reflect.Method protoNameMethod = PROTO_CMD_NAME_METHOD;
                    if (protoNameMethod == null) {
                        protoNameMethod = protocolCmd.getClass().getMethod("name");
                        PROTO_CMD_NAME_METHOD = protoNameMethod;
                    }
                    Object nameResult = protoNameMethod.invoke(protocolCmd);
                    if (nameResult instanceof String) return (String) nameResult;
                }
            } catch (Throwable ignored) {}
        }

        // 폴백: toString() 첫 단어
        String str = commandArg.toString();
        int space = str.indexOf(' ');
        String candidate = space > 0 ? str.substring(0, space) : str;
        return candidate.length() <= 20 ? candidate : "REDIS";
    }

    /**
     * Connection에서 host/port 추출. Method는 첫 호출 시 캐싱.
     * Jedis 3.x: getHost(), getPort()
     * Jedis 4.x: getHostAndPort() → HostAndPort
     */
    private static void extractPeerInfo(Object connection, Span span) {
        // Jedis 3.x
        java.lang.reflect.Method getHostMethod = GET_HOST_METHOD;
        java.lang.reflect.Method getPortMethod = GET_PORT_METHOD;
        if (getHostMethod == null) {
            try {
                getHostMethod = connection.getClass().getMethod("getHost");
                GET_HOST_METHOD = getHostMethod;
                getPortMethod = connection.getClass().getMethod("getPort");
                GET_PORT_METHOD = getPortMethod;
            } catch (Throwable ignored) {}
        }
        if (getHostMethod != null) {
            try {
                String host = (String) getHostMethod.invoke(connection);
                int port = (int) getPortMethod.invoke(connection);
                if (host != null) {
                    span.setAttribute("server.address", host);
                    span.setAttribute("server.port", (long) port);
                    span.setAttribute("peer.service", "redis-" + host);
                }
                return;
            } catch (Throwable ignored) {}
        }

        // Jedis 4.x: getHostAndPort()
        java.lang.reflect.Method getHapMethod = GET_HAP_METHOD;
        if (getHapMethod == null) {
            try {
                getHapMethod = connection.getClass().getMethod("getHostAndPort");
                GET_HAP_METHOD = getHapMethod;
            } catch (Throwable ignored) {}
        }
        if (getHapMethod != null) {
            try {
                Object hap = getHapMethod.invoke(connection);
                if (hap != null) {
                    java.lang.reflect.Method hapGetHost = HAP_GET_HOST_METHOD;
                    java.lang.reflect.Method hapGetPort = HAP_GET_PORT_METHOD;
                    if (hapGetHost == null) {
                        hapGetHost = hap.getClass().getMethod("getHost");
                        HAP_GET_HOST_METHOD = hapGetHost;
                        hapGetPort = hap.getClass().getMethod("getPort");
                        HAP_GET_PORT_METHOD = hapGetPort;
                    }
                    String host = (String) hapGetHost.invoke(hap);
                    int port = (int) hapGetPort.invoke(hap);
                    span.setAttribute("server.address", host);
                    span.setAttribute("server.port", (long) port);
                    span.setAttribute("peer.service", "redis-" + host);
                }
            } catch (Throwable ignored) {}
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

    private JedisAdvice() {}
}

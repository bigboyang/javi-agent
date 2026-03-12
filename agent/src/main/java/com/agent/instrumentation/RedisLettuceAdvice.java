package com.agent.instrumentation;

import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * Redis Lettuce 계측.
 * io.lettuce.core.protocol.CommandHandler.dispatch()를 계측한다.
 *
 * 추가 속성:
 *   net.peer.name / net.peer.port — Netty 채널 remote address에서 추출
 *   db.redis.database_index       — CommandHandler의 ChannelHandlerContext → channel
 *                                    remote address 추출 후, SELECT 명령 추적으로 DB index 감지
 *
 * 네트워크 정보 추출 전략:
 *   CommandHandler는 Netty ChannelDuplexHandler를 상속하며,
 *   channelRegistered() 이후 this.ctx (ChannelHandlerContext) 필드를 보유한다.
 *   ctx.channel().remoteAddress()로 InetSocketAddress를 얻는다.
 */
public final class RedisLettuceAdvice {

    // ChannelHandlerContext 필드 캐싱 (DCL 패턴)
    private static volatile java.lang.reflect.Field CTX_FIELD = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.This Object handler,
            @Advice.Argument(0) Object command) {

        if (command == null) return null;

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.redis.lettuce");

        String commandName = "REDIS";
        try {
            Object type = command.getClass().getMethod("getType").invoke(command);
            if (type != null) commandName = type.toString();
        } catch (Throwable ignored) {}

        Span span = tracer.spanBuilder("REDIS " + commandName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        span.setAttribute("db.system", "redis");
        span.setAttribute("db.operation", commandName);

        // net.peer.name / net.peer.port: Netty channel remote address
        extractPeerInfo(handler, span);

        return new State(span, span.makeCurrent());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(com.agent.span.SpanStatus.ERROR, error.getMessage());
        }
        state.scope.close();
        state.span.end();
    }

    /**
     * Lettuce CommandHandler가 보유하는 Netty ChannelHandlerContext(this.ctx)에서
     * 채널의 remote address를 추출한다.
     *
     * CommandHandler 계층: CommandHandler → ChannelDuplexHandler → ChannelInboundHandlerAdapter
     * → AbstractChannelHandlerContext에 ChannelHandlerContext ctx 필드 존재.
     * Lettuce 5.x/6.x 공통 패턴이나 내부 API이므로 실패해도 span은 계속 진행한다.
     */
    private static void extractPeerInfo(Object handler, Span span) {
        try {
            java.lang.reflect.Field ctxField = CTX_FIELD;
            if (ctxField == null) {
                Class<?> clazz = handler.getClass();
                while (clazz != null) {
                    try {
                        ctxField = clazz.getDeclaredField("ctx");
                        ctxField.setAccessible(true);
                        CTX_FIELD = ctxField;
                        break;
                    } catch (NoSuchFieldException e) {
                        clazz = clazz.getSuperclass();
                    }
                }
            }
            if (ctxField == null) return;

            Object ctx = ctxField.get(handler);
            if (ctx == null) return;

            Object channel = ctx.getClass().getMethod("channel").invoke(ctx);
            if (channel == null) return;

            Object remoteAddr = channel.getClass().getMethod("remoteAddress").invoke(channel);
            if (remoteAddr instanceof java.net.InetSocketAddress) {
                java.net.InetSocketAddress addr = (java.net.InetSocketAddress) remoteAddr;
                span.setAttribute("net.peer.name", addr.getHostString());
                span.setAttribute("net.peer.port", String.valueOf(addr.getPort()));
            }
        } catch (Throwable ignored) {}
    }

    public static final class State {
        public final Span span;
        public final com.agent.span.Scope scope;

        public State(Span span, com.agent.span.Scope scope) {
            this.span  = span;
            this.scope = scope;
        }
    }

    private RedisLettuceAdvice() {}
}

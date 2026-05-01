package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.JedisAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Jedis Redis 클라이언트 계측 플러그인.
 *
 * Jedis 4.x: Connection.executeCommand(CommandArguments) — send+receive 통합, 완전한 round-trip.
 * Jedis 3.x: Connection.sendCommand(ProtocolCommand, byte[]...) — 전송 단계만 계측.
 *
 * 두 버전 모두 동일한 JedisAdvice 클래스를 사용한다.
 */
public class JedisInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "redis-jedis";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        // Jedis 4.x: executeCommand(CommandArguments) — 완전한 round-trip
        agentBuilder
                .type(named("redis.clients.jedis.Connection"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(JedisAdvice.class)
                                .on(named("executeCommand").and(takesArguments(1)))))
                .installOn(inst);

        // Jedis 3.x: sendCommand(ProtocolCommand, byte[]...) — 전송 단계
        agentBuilder
                .type(named("redis.clients.jedis.Connection"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(JedisAdvice.class)
                                .on(named("sendCommand")
                                        .and(takesArgument(0,
                                                named("redis.clients.jedis.ProtocolCommand"))))))
                .installOn(inst);
    }
}

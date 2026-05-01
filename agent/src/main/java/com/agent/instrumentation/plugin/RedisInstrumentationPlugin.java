package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.RedisLettuceAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class RedisInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "redis-lettuce";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                // CommandHandler 단독 계측. RedisChannelHandler를 OR에서 제거하는 이유:
                //
                // Lettuce 6.x 클래스 계층 (실측):
                //   StatefulRedisConnectionImpl extends RedisChannelHandler (abstract)
                //   CommandHandler extends ChannelDuplexHandler  ← Netty pipeline 핸들러
                //
                // 호출 경로:
                //   사용자 코드 → StatefulRedisConnectionImpl.dispatch()
                //     → invokespecial RedisChannelHandler.dispatch()  [protected]
                //       → channelWriter(DefaultEndpoint).write()
                //         → Netty pipeline → CommandHandler.write()   [Netty I/O]
                //
                // CommandHandler에는 dispatch() 메서드가 없다. (javap 실측 확인)
                // 따라서 ElementMatchers.named("dispatch")는 CommandHandler 클래스에 걸리지 않고,
                // OR 조건의 CommandHandler 브랜치는 transform에서 아무 메서드도 계측하지 못한다.
                // (dead branch — 오버헤드만 발생, span 중복은 없음)
                //
                // RedisChannelHandler.dispatch()가 실제 계측 지점이다:
                //   - 사용자 코드에서 직접 호출되는 첫 번째 내부 경계
                //   - StatefulRedisConnectionImpl (서브클래스)의 vtable을 통해 한 번만 호출됨
                //   - @Advice.This가 StatefulRedisConnectionImpl 인스턴스를 바인딩
                //     → RedisLettuceAdvice의 ctx 필드 탐색은 superclass 순회로 처리됨
                //       (RedisChannelHandler 자체에는 ctx 없음 — 항상 null, 무해하게 skip)
                //
                // peer 정보(net.peer.name/port)는 현재 extractPeerInfo()가 ctx 필드에 의존하므로
                // RedisChannelHandler 계측 시 ctx를 얻지 못한다. 이는 별도 개선 과제.
                // (CommandHandler.channelRegistered() 시점에 remoteAddress를 캡처해 공유하는 방식 고려)
                .type(ElementMatchers.named("io.lettuce.core.RedisChannelHandler"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RedisLettuceAdvice.class).on(ElementMatchers.named("dispatch"))))
                .installOn(inst);
    }
}

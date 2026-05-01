package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.WebClientAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Spring WebClient 계측 플러그인.
 *
 * 대상: ExchangeFunctions$DefaultExchangeFunction.exchange(ClientRequest)
 *
 * DefaultExchangeFunction은 ExchangeFunctions의 private static inner class이므로
 * 클래스명에 '$' 구분자를 사용한다.
 */
public class WebClientInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "spring-webclient";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(named("org.springframework.web.reactive.function.client.ExchangeFunctions$DefaultExchangeFunction"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(WebClientAdvice.class)
                                .on(named("exchange")
                                        .and(takesArguments(1))
                                        .and(takesArgument(0, named(
                                                "org.springframework.web.reactive.function.client.ClientRequest"))))))
                .installOn(inst);
    }
}

package com.agent.instrumentation.plugin;

import com.agent.instrumentation.FeignClientAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Feign HTTP 클라이언트 계측 플러그인.
 *
 * feign.Client 인터페이스의 모든 구현체를 대상으로 한다:
 *   - feign.Client.Default        (Java HttpURLConnection)
 *   - feign.httpclient.ApacheHttpClient
 *   - feign.okhttp.OkHttpClient
 *   - feign.hc5.ApacheHttp5Client
 *   - 기타 사용자 정의 구현체
 *
 * isSubTypeOf + not(isInterface()) 조합으로 인터페이스는 제외하고
 * 실제 구현 클래스만 계측한다.
 */
public class FeignClientInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "feign-client";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                .type(hasSuperType(named("feign.Client")).and(not(isInterface())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(FeignClientAdvice.class)
                                .on(named("execute")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, named("feign.Request")))
                                        .and(takesArgument(1, named("feign.Request$Options"))))))
                .installOn(inst);
    }
}

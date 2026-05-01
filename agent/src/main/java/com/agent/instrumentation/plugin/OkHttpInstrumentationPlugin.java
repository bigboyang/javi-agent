package com.agent.instrumentation.plugin;

import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.instrumentation.OkHttpAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * OkHttp3 HTTP 클라이언트 계측 플러그인.
 *
 * OkHttp 3.x: okhttp3.RealCall.execute()
 * OkHttp 4.x: okhttp3.internal.connection.RealCall.execute()
 *
 * 두 클래스명을 모두 등록하여 버전 무관 지원.
 */
public class OkHttpInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "okhttp";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        // OkHttp 4.x (Kotlin 기반 내부 클래스)
        agentBuilder
                .type(named("okhttp3.internal.connection.RealCall"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OkHttpAdvice.class)
                                .on(named("execute").and(takesArguments(0)))))
                .installOn(inst);

        // OkHttp 3.x
        agentBuilder
                .type(named("okhttp3.RealCall"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(OkHttpAdvice.class)
                                .on(named("execute").and(takesArguments(0)))))
                .installOn(inst);
    }
}

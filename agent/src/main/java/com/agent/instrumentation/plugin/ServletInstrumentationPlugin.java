package com.agent.instrumentation.plugin;

import com.agent.instrumentation.HttpServletAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

public class ServletInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public int order() {
        return 10;
    }

    @Override
    public String name() {
        return "servlet";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        // javax.servlet (Spring Boot 2.x, Tomcat 9 이하)
        agentBuilder
                .type(
                    ElementMatchers.hasSuperType(
                        ElementMatchers.named("javax.servlet.http.HttpServlet"))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract())))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpServletAdvice.class)
                                        .on(ElementMatchers.named("service")
                                                .and(ElementMatchers.takesArguments(2))
                                                .and(ElementMatchers.takesArgument(0,
                                                        ElementMatchers.named("javax.servlet.http.HttpServletRequest")))
                                                .and(ElementMatchers.takesArgument(1,
                                                        ElementMatchers.named("javax.servlet.http.HttpServletResponse"))))))
                .installOn(inst);

        // jakarta.servlet — Spring MVC: FrameworkServlet이 service()를 직접 정의
        agentBuilder
                .type(ElementMatchers.named("org.springframework.web.servlet.FrameworkServlet"))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpServletAdvice.class)
                                        .on(ElementMatchers.named("service")
                                                .and(ElementMatchers.takesArguments(2))
                                                .and(ElementMatchers.takesArgument(0,
                                                        ElementMatchers.named("jakarta.servlet.http.HttpServletRequest")))
                                                .and(ElementMatchers.takesArgument(1,
                                                        ElementMatchers.named("jakarta.servlet.http.HttpServletResponse"))))))
                .installOn(inst);

        // jakarta.servlet — non-Spring concrete servlets (e.g. H2 web console)
        agentBuilder
                .type(
                    ElementMatchers.hasSuperType(
                        ElementMatchers.named("jakarta.servlet.http.HttpServlet"))
                    .and(ElementMatchers.not(ElementMatchers.isInterface()))
                    .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                    .and(ElementMatchers.not(ElementMatchers.nameStartsWith("org.springframework."))))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(HttpServletAdvice.class)
                                        .on(ElementMatchers.named("service")
                                                .and(ElementMatchers.takesArguments(2))
                                                .and(ElementMatchers.takesArgument(0,
                                                        ElementMatchers.named("jakarta.servlet.http.HttpServletRequest")))
                                                .and(ElementMatchers.takesArgument(1,
                                                        ElementMatchers.named("jakarta.servlet.http.HttpServletResponse"))))))
                .installOn(inst);
    }
}

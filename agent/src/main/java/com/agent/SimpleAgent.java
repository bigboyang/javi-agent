package com.agent;

import com.agent.instrumentation.AgentRuntime;
import com.agent.instrumentation.InstrumentationPlugin;
import com.agent.logs.AgentLogger;
import com.agent.logs.AppLogCollector;
import com.agent.metric.JvmMetricsCollector;
import com.agent.metric.K8sMetricsCollector;
import com.agent.metric.MetricsCollectorScheduler;
import com.agent.profiling.ProfilingScheduler;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.agent.builder.AgentBuilder;

public class SimpleAgent {

    public static void premain(String agentArgs, Instrumentation inst) {
        AgentLogger.info("========================================");
        AgentLogger.info("Javi Agent가 시작되었습니다!");
        AgentLogger.info("로그 파일: " + AgentLogger.logFilePath());
        AgentLogger.info("========================================");

        if (agentArgs != null && !agentArgs.isEmpty()) {
            AgentLogger.info("Agent 인자: " + agentArgs);
        }

        AgentRuntime.provider();

        AgentBuilder.Listener transformListener = new AgentBuilder.Listener.Adapter() {
            @Override
            public void onError(String typeName, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, Throwable throwable) {
                AgentLogger.error("Transformation error on " + typeName + ": " + throwable.getMessage());
            }
            @Override
            public void onTransformation(net.bytebuddy.description.type.TypeDescription typeDescription, ClassLoader classLoader, net.bytebuddy.utility.JavaModule module, boolean loaded, net.bytebuddy.dynamic.DynamicType dynamicType) {
                AgentLogger.info("Transformed: " + typeDescription.getName());
            }
        };

        AgentBuilder agentBuilder = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(transformListener);

        AgentBuilder bootstrapBuilder = buildBootstrapBuilder(inst, transformListener);

        installPlugins(inst, agentBuilder, bootstrapBuilder);

        AppLogCollector.install();
        JvmMetricsCollector.start();
        K8sMetricsCollector.start();
        MetricsCollectorScheduler.start();
        ProfilingScheduler.start();

        AgentLogger.info("모든 계측이 등록되었습니다.");
        AgentLogger.info("이제 애플리케이션이 시작됩니다...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AgentLogger.info("Shutdown: 남은 span/metric/log flush 중...");
            try {
                MetricsCollectorScheduler.stop();
                ProfilingScheduler.stop();
                AgentRuntime.provider().forceFlush().join(5, TimeUnit.SECONDS);
                AgentRuntime.provider().shutdown().join(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                AgentLogger.error("Shutdown 중 오류: " + e.getMessage(), e);
            }
            AgentLogger.info("Shutdown 완료.");
            AgentLogger.flush();
        }, "javi-agent-shutdown"));
    }

    private static AgentBuilder buildBootstrapBuilder(Instrumentation inst, AgentBuilder.Listener listener) {
        try {
            File tmpDir = Files.createTempDirectory("javi-bootstrap").toFile();
            return new AgentBuilder.Default()
                    .with(new AgentBuilder.InjectionStrategy.UsingInstrumentation(inst, tmpDir))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(listener);
        } catch (IOException e) {
            AgentLogger.warn("Bootstrap injection 디렉토리 생성 실패, 비동기 계측 일부 제한: " + e.getMessage());
            return null;
        }
    }

    private static void installPlugins(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        ServiceLoader<InstrumentationPlugin> loader =
                ServiceLoader.load(InstrumentationPlugin.class, SimpleAgent.class.getClassLoader());

        loader.stream()
                .map(ServiceLoader.Provider::get)
                .sorted(Comparator.comparingInt(InstrumentationPlugin::order))
                .forEach(plugin -> {
                    if (plugin.requiresBootstrap() && bootstrapBuilder == null) {
                        AgentLogger.warn("[agent] skip " + plugin.name() + " — bootstrap builder 없음");
                        return;
                    }
                    try {
                        plugin.install(inst, agentBuilder, bootstrapBuilder);
                        AgentLogger.info("[agent] installed: " + plugin.name());
                    } catch (Exception e) {
                        AgentLogger.error("[agent] 계측 설치 실패: " + plugin.name() + " — " + e.getMessage(), e);
                    }
                });
    }
}

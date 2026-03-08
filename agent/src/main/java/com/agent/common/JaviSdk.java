package com.agent.common;

import com.agent.logs.SdkLoggerProvider;
import com.agent.metric.SdkMeterProvider;
import com.agent.trace.SdkTracerProvider;
import java.util.concurrent.CompletableFuture;

/**
 * 에이전트의 모든 신호(Trace, Log, Metric)를 관리하는 중앙 SDK 객체.
 * Datadog/OpenTelemetry와 유사한 구조로 설계됨.
 */
public final class JaviSdk {
    private static volatile JaviSdk instance;

    private final SdkTracerProvider tracerProvider;
    private final SdkLoggerProvider loggerProvider;
    private final SdkMeterProvider meterProvider;

    private JaviSdk(SdkTracerProvider tracerProvider, 
                    SdkLoggerProvider loggerProvider, 
                    SdkMeterProvider meterProvider) {
        this.tracerProvider = tracerProvider;
        this.loggerProvider = loggerProvider;
        this.meterProvider = meterProvider;
    }

    public static void initialize(SdkTracerProvider tp, SdkLoggerProvider lp, SdkMeterProvider mp) {
        if (instance == null) {
            synchronized (JaviSdk.class) {
                if (instance == null) {
                    instance = new JaviSdk(tp, lp, mp);
                }
            }
        }
    }

    public static JaviSdk get() {
        return instance;
    }

    public SdkTracerProvider getTracerProvider() { return tracerProvider; }
    public SdkLoggerProvider getLoggerProvider() { return loggerProvider; }
    public SdkMeterProvider getMeterProvider() { return meterProvider; }

    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.allOf(
            tracerProvider.shutdown().toCompletableFuture(),
            loggerProvider.shutdown(),
            meterProvider.shutdown()
        );
    }
}

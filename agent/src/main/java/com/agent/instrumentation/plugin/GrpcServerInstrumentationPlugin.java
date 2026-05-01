package com.agent.instrumentation.plugin;

import com.agent.instrumentation.GrpcServerAdvice;
import com.agent.instrumentation.InstrumentationPlugin;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class GrpcServerInstrumentationPlugin implements InstrumentationPlugin {

    @Override
    public String name() {
        return "grpc-server";
    }

    @Override
    public void install(Instrumentation inst, AgentBuilder agentBuilder, AgentBuilder bootstrapBuilder) {
        agentBuilder
                // nameContains("ServerCalls$")에서 nameStartsWith("io.grpc.stub.ServerCalls$")로 교체.
                //
                // nameContains 위험성:
                //   패키지 경로 어디에든 "ServerCalls$"가 포함된 클래스를 매치한다.
                //   예: com.myapp.grpc.ServerCalls$CustomListener, com.thirdparty.ServerCalls$Helper
                //   실제 gRPC 라이브러리 전체를 검색해도 "ServerCalls$"는 io.grpc.stub 패키지에만
                //   존재하지만(실측), 사용자 앱 내부에 동명 클래스가 있으면 의도치 않게 계측된다.
                //
                // 계측 대상 내부 클래스 (grpc-stub 1.78.0 실측, io.grpc.stub.ServerCalls$ 하위):
                //   UnaryServerCallHandler$UnaryServerCallListener      extends ServerCall$Listener
                //   StreamingServerCallHandler$StreamingServerCallListener extends ServerCall$Listener
                //   (NoopStreamObserver, ServerCallStreamObserverImpl 등은 Listener 상속 아님)
                //
                // nameStartsWith("io.grpc.stub.ServerCalls$")는 위 두 클래스만 선별하며
                // hasSuperType(named("io.grpc.ServerCall$Listener")) 조건이 추가 보호막이 된다.
                //
                // hasSuperType 유지 이유:
                //   gRPC 버전 업그레이드 또는 사용자 정의 ServerCalls$ 내부 클래스가
                //   추가될 경우 Listener 구현 클래스에만 Advice가 적용됨을 보장한다.
                //   또한 onHalfClose() 계측은 ServerCall$Listener 계약에 의존하므로
                //   타입 보증이 필요하다.
                .type(nameStartsWith("io.grpc.stub.ServerCalls$")
                        .and(hasSuperType(named("io.grpc.ServerCall$Listener"))))
                .transform((builder, type, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(GrpcServerAdvice.class)
                                .on(named("onHalfClose").and(takesNoArguments()))))
                .installOn(inst);
    }
}

package com.agent.instrumentation;

import com.agent.logs.AgentLogger;
import com.agent.span.Context;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanContext;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;
import org.slf4j.MDC;

import java.lang.reflect.Method;

/**
 * Elasticsearch REST Client 계측.
 *
 * 대상: org.elasticsearch.client.RestClient.performRequest(Request)
 *
 * 선택 근거:
 * - RestClient는 low-level 클라이언트로, 고수준 클라이언트(RestHighLevelClient, ElasticsearchClient)
 *   모두 내부적으로 RestClient.performRequest()를 호출한다. 단일 진입점으로 모든 클라이언트 버전을 커버.
 * - performRequest(Request) — 동기 버전 (ES 6.x+)
 * - performRequestAsync(Request, ResponseListener) — 비동기 버전 (별도 처리 필요, 현재 미구현)
 *
 * Request 객체에서 추출 가능한 정보:
 * - getMethod()   -> "GET", "POST", "PUT", "DELETE" 등
 * - getEndpoint() -> "/users/_search", "/_bulk" 등
 * - getParameters() -> Map<String, String> (쿼리 파라미터)
 *
 * Span 명: "elasticsearch METHOD /endpoint" (예: "elasticsearch POST /orders/_search")
 * endpoint가 없으면 "elasticsearch unknown"으로 fallback.
 *
 * 엔드포인트 정규화:
 * - /index-name/_doc/12345 같은 동적 경로에서 숫자 ID를 제거하지 않는다.
 * - 카디널리티 문제가 있다면 상위 레벨(예: /_doc/까지만)로 정규화하는 로직 추가를 권장.
 *
 * 성능:
 * - Request.getMethod()/getEndpoint()는 단순 getter이므로 오버헤드 무시 가능.
 * - reflection 메서드 캐싱으로 첫 호출 이후 비용 최소화.
 */
public final class ElasticsearchAdvice {

    private static volatile Method GET_METHOD = null;
    private static volatile Method GET_ENDPOINT = null;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(@Advice.Argument(0) Object request) {

        Span parentSpan = Context.currentSpan();
        if (!parentSpan.isRecording()) return null;

        if (request == null) return null;

        String httpMethod = extractMethod(request);
        String endpoint = extractEndpoint(request);

        // span name 구성
        String spanName;
        if (httpMethod != null && endpoint != null) {
            // endpoint가 너무 길면 잘라서 span 이름 폭발 방지 (카디널리티)
            String trimmedEndpoint = endpoint.length() > 100 ? endpoint.substring(0, 100) : endpoint;
            spanName = "elasticsearch " + httpMethod + " " + trimmedEndpoint;
        } else if (httpMethod != null) {
            spanName = "elasticsearch " + httpMethod;
        } else {
            spanName = "elasticsearch request";
        }

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.elasticsearch");
        Span span = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();

        // OTel DB 시맨틱 속성 (Elasticsearch는 db.system=elasticsearch로 분류)
        span.setAttribute("db.system", "elasticsearch");
        if (httpMethod != null) span.setAttribute("elasticsearch.method", httpMethod);
        if (endpoint != null)   span.setAttribute("elasticsearch.endpoint", endpoint);

        // http.url 조합 — 가능하면 호스트 정보 추가 (RestClient에서 접근 시도)
        // RestClient는 host 목록을 내부에 보유하므로 첫 번째 노드 정보를 추출 시도
        String baseUrl = extractBaseUrl(request);
        if (baseUrl != null && endpoint != null) {
            span.setAttribute("http.url", baseUrl + endpoint);
        }

        Scope scope = span.makeCurrent();

        String prevTraceId = MDC.get("traceId");
        String prevSpanId = MDC.get("spanId");
        SpanContext ctx = span.getContext();
        if (ctx != null && ctx.isValid()) {
            MDC.put("traceId", ctx.getTraceId());
            MDC.put("spanId", ctx.getSpanId());
        }

        AgentLogger.debug("[Elasticsearch] span started: " + spanName);
        return new State(span, scope, prevTraceId, prevSpanId);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter State state,
            @Advice.Return Object response,
            @Advice.Thrown Throwable error) {

        if (state == null) return;

        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
        } else if (response != null) {
            // Response에서 HTTP 상태 코드 추출 시도
            try {
                Object statusLine = response.getClass().getMethod("getStatusLine").invoke(response);
                if (statusLine != null) {
                    int statusCode = (int) statusLine.getClass().getMethod("getStatusCode").invoke(statusLine);
                    state.span.setAttribute("http.response.status_code", String.valueOf(statusCode));
                    if (statusCode >= 400) {
                        state.span.setStatus(SpanStatus.ERROR, "HTTP " + statusCode);
                    }
                }
            } catch (Throwable ignored) {}
        }

        state.scope.close();
        state.span.end();

        if (state.prevTraceId == null) MDC.remove("traceId");
        else MDC.put("traceId", state.prevTraceId);
        if (state.prevSpanId == null) MDC.remove("spanId");
        else MDC.put("spanId", state.prevSpanId);
    }

    // --- reflection 헬퍼 ---

    private static String extractMethod(Object request) {
        try {
            Method m = GET_METHOD;
            if (m == null) {
                m = request.getClass().getMethod("getMethod");
                GET_METHOD = m;
            }
            return (String) m.invoke(request);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractEndpoint(Object request) {
        try {
            Method m = GET_ENDPOINT;
            if (m == null) {
                m = request.getClass().getMethod("getEndpoint");
                GET_ENDPOINT = m;
            }
            return (String) m.invoke(request);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Request 객체 자체에는 baseUrl이 없다.
     * RestClient는 호출 시점에 호스트를 선택하므로 Request 단계에서는 URL을 알 수 없다.
     * 이 메서드는 확장 가능성을 위해 남겨두되, 현재는 null을 반환한다.
     * 실제 URL이 필요하다면 RestClient 자체를 계측(@This 사용)하거나
     * Response에서 getHost()를 읽어 onExit에서 설정하는 방식이 더 적합하다.
     */
    private static String extractBaseUrl(Object request) {
        return null;
    }

    public static final class State {
        public final Span span;
        public final Scope scope;
        public final String prevTraceId;
        public final String prevSpanId;

        public State(Span span, Scope scope, String prevTraceId, String prevSpanId) {
            this.span = span;
            this.scope = scope;
            this.prevTraceId = prevTraceId;
            this.prevSpanId = prevSpanId;
        }
    }

    private ElasticsearchAdvice() {}
}

package com.agent.instrumentation;

import com.agent.common.utils.SqlSanitizer;
import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for JDBC Statement execute methods (with explicit SQL argument).
 *
 * 대상: Statement.execute(String), executeQuery(String), executeUpdate(String)
 *
 * db.system: Connection.getMetaData().getDatabaseProductName()으로 실제 DB 벤더 감지
 *   (MySQL/PostgreSQL/Oracle/etc.)
 * db.name, net.peer.name, net.peer.port: JDBC URL 파싱으로 추출
 * db.user: Connection.getMetaData().getUserName()
 */
public final class JdbcStatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State onEnter(
            @Advice.This Object stmt,
            @Advice.Argument(0) String sql) {

        Tracer tracer = AgentRuntime.getTracer("com.agent.instrumentation.jdbc");
        String maskedSql = SqlSanitizer.sanitize(sql);

        String spanName;
        if (maskedSql == null || maskedSql.isEmpty()) {
            spanName = "DB Query";
        } else {
            String trimmed = maskedSql.trim();
            spanName = trimmed.length() > 60 ? trimmed.substring(0, 60) + "..." : trimmed;
        }

        Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
        if (maskedSql != null) span.setAttribute("db.query.text", maskedSql);

        // DB 메타데이터 추출: 실제 DB 벤더, db.name, 네트워크 정보
        String dbSystem = "other_sql";
        try {
            Object conn = stmt.getClass().getMethod("getConnection").invoke(stmt);
            if (conn != null) dbSystem = extractConnMetadata(conn, span);
        } catch (Throwable ignored) {}
        span.setAttribute("db.system", dbSystem);

        String dbOperation = extractDbOperation(sql);

        Scope scope = span.makeCurrent();
        AgentLogger.debug("[JDBC] span started: " + maskedSql);
        return new State(span, scope, dbSystem, dbOperation, System.nanoTime());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter State state, @Advice.Thrown Throwable error) {
        if (state == null) return;
        if (error != null) {
            state.span.recordException(error);
            state.span.setStatus(SpanStatus.ERROR, error.getMessage());
            AgentLogger.debug("[JDBC] span error: " + error.getMessage());
        }
        state.scope.close();
        state.span.end();
        recordDbMetrics(state, error);
    }

    private static void recordDbMetrics(State state, Throwable error) {
        try {
            long durationMs = (System.nanoTime() - state.startNano) / 1_000_000L;
            java.util.Map<String, String> tags = new java.util.HashMap<>(4);
            tags.put("db.system", state.dbSystem);
            tags.put("db.operation", state.dbOperation);

            com.agent.metric.MetricRegistry reg = com.agent.metric.MetricRegistry.get();
            reg.counter("db.client.operation.count", tags).increment();
            reg.histogram("db.client.operation.duration", tags).record(durationMs);
            if (error != null) {
                reg.counter("db.client.operation.error.count", tags).increment();
            }
        } catch (Throwable ignored) {}
    }

    // --- Connection 메타데이터 헬퍼 ---

    static String extractConnMetadata(Object conn, Span span) {
        try {
            Object meta = conn.getClass().getMethod("getMetaData").invoke(conn);
            if (meta == null) return "other_sql";

            String productName = (String) meta.getClass()
                    .getMethod("getDatabaseProductName").invoke(meta);
            String dbSystem = resolveDbSystem(productName);

            try {
                String url = (String) meta.getClass().getMethod("getURL").invoke(meta);
                parseJdbcUrl(url, span);
            } catch (Throwable ignored) {}

            try {
                String user = (String) meta.getClass().getMethod("getUserName").invoke(meta);
                if (user != null && !user.isEmpty()) span.setAttribute("db.user", user);
            } catch (Throwable ignored) {}

            return dbSystem;
        } catch (Throwable t) {
            return "other_sql";
        }
    }

    static String resolveDbSystem(String productName) {
        if (productName == null) return "other_sql";
        String lc = productName.toLowerCase();
        if (lc.contains("mysql"))                                   return "mysql";
        if (lc.contains("postgresql") || lc.contains("postgres"))  return "postgresql";
        if (lc.contains("oracle"))                                  return "oracle";
        if (lc.contains("microsoft") || lc.contains("sql server")) return "mssql";
        if (lc.contains("h2"))                                      return "h2";
        if (lc.contains("sqlite"))                                  return "sqlite";
        if (lc.contains("mariadb"))                                 return "mariadb";
        return "other_sql";
    }

    /**
     * JDBC URL에서 db.name, net.peer.name, net.peer.port를 파싱한다.
     * 형식: jdbc:vendor://host:port/dbname?params
     */
    static void parseJdbcUrl(String url, Span span) {
        if (url == null) return;
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return;
            String rest = url.substring(schemeEnd + 3);

            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);

            int slash = rest.indexOf('/');
            String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
            String dbName   = slash >= 0 && slash < rest.length() - 1
                    ? rest.substring(slash + 1) : null;

            if (dbName != null && !dbName.isEmpty()) span.setAttribute("db.name", dbName);

            // IPv6: [::1]:5432
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close >= 0) {
                    span.setAttribute("net.peer.name", hostPort.substring(1, close));
                    if (close + 2 < hostPort.length())
                        span.setAttribute("net.peer.port", hostPort.substring(close + 2));
                }
            } else {
                int colon = hostPort.lastIndexOf(':');
                if (colon >= 0) {
                    span.setAttribute("net.peer.name", hostPort.substring(0, colon));
                    span.setAttribute("net.peer.port", hostPort.substring(colon + 1));
                } else if (!hostPort.isEmpty()) {
                    span.setAttribute("net.peer.name", hostPort);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** SQL 첫 단어에서 db.operation 추출 (카디널리티 낮음: SELECT/INSERT/UPDATE/DELETE 등) */
    static String extractDbOperation(String sql) {
        if (sql == null || sql.isEmpty()) return "UNKNOWN";
        String trimmed = sql.trim();
        int space = trimmed.indexOf(' ');
        String first = (space > 0 ? trimmed.substring(0, space) : trimmed).toUpperCase();
        switch (first) {
            case "SELECT": case "INSERT": case "UPDATE": case "DELETE":
            case "CREATE": case "DROP":  case "ALTER":  case "CALL":
            case "MERGE":  case "UPSERT": return first;
            default: return "OTHER";
        }
    }

    public static final class State {
        public final Span   span;
        public final Scope  scope;
        public final String dbSystem;
        public final String dbOperation;
        public final long   startNano;

        public State(Span span, Scope scope, String dbSystem, String dbOperation, long startNano) {
            this.span        = span;
            this.scope       = scope;
            this.dbSystem    = dbSystem;
            this.dbOperation = dbOperation;
            this.startNano   = startNano;
        }
    }

    private JdbcStatementAdvice() {}
}

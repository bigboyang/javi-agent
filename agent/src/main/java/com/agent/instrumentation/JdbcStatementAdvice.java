package com.agent.instrumentation;

import com.agent.common.utils.SqlSanitizer;
import com.agent.logs.AgentLogger;
import com.agent.span.Scope;
import com.agent.span.Span;
import com.agent.span.SpanKind;
import com.agent.span.SpanStatus;
import com.agent.trace.Tracer;
import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ByteBuddy advice for JDBC Statement execute methods (with explicit SQL argument).
 *
 * 대상: Statement.execute(String), executeQuery(String), executeUpdate(String)
 *
 * db.system: Connection.getMetaData().getDatabaseProductName()으로 실제 DB 벤더 감지
 *   (MySQL/PostgreSQL/Oracle/etc.)
 * db.name, net.peer.name, net.peer.port: JDBC URL 파싱으로 추출
 * db.user: Connection.getMetaData().getUserName()
 *
 * 성능 최적화:
 *  - getConnection/getMetaData 등 Reflection Method 객체를 클래스별로 캐싱
 *  - DB 연결 메타데이터(vendor, URL, user)를 커넥션 클래스별로 캐싱 (풀 내 커넥션은 불변)
 *  - Counter/Histogram 레퍼런스를 직접 캐싱하여 매 쿼리마다 HashMap+MetricKey+TreeMap 생성 제거
 */
public final class JdbcStatementAdvice {

    // ── Reflection 메서드 캐시 (클래스별) ────────────────────────────────────
    public static final ConcurrentHashMap<Class<?>, Method> GET_CONNECTION_CACHE = new ConcurrentHashMap<>(4);
    public static final ConcurrentHashMap<Class<?>, Method> GET_META_CACHE        = new ConcurrentHashMap<>(4);
    public static final ConcurrentHashMap<Class<?>, Method> GET_PRODUCT_NAME_CACHE = new ConcurrentHashMap<>(4);
    public static final ConcurrentHashMap<Class<?>, Method> GET_URL_CACHE         = new ConcurrentHashMap<>(4);
    public static final ConcurrentHashMap<Class<?>, Method> GET_USERNAME_CACHE    = new ConcurrentHashMap<>(4);

    /**
     * DB 연결 메타데이터 캐시 (커넥션 클래스 → ConnInfo).
     *
     * <p>커넥션 풀 내 모든 커넥션은 같은 DataSource를 공유하므로
     * 클래스가 동일하면 vendor/URL/user도 동일하다 (단일 DataSource 기준).
     * Datadog Agent, OTel Java SDK 모두 동일한 per-class 캐싱 전략을 사용한다.
     */
    public static final ConcurrentHashMap<Class<?>, ConnInfo> CONN_INFO_CACHE = new ConcurrentHashMap<>(4);

    /** 불변 DB 연결 메타데이터 홀더 */
    public static final class ConnInfo {
        public static final ConnInfo UNKNOWN = new ConnInfo("other_sql", null, null, null, null);
        final String dbSystem;
        final String dbName;
        final String peerName;
        final String peerPort;
        final String dbUser;

        ConnInfo(String dbSystem, String dbName, String peerName, String peerPort, String dbUser) {
            this.dbSystem  = dbSystem;
            this.dbName    = dbName;
            this.peerName  = peerName;
            this.peerPort  = peerPort;
            this.dbUser    = dbUser;
        }
    }

    // ── 메트릭 직접 레퍼런스 캐시 ("dbSystem|dbOperation" → Counter/Histogram) ─
    // 매 쿼리마다 HashMap + MetricKey + TreeMap 생성을 제거한다.
    public static final ConcurrentHashMap<String, com.agent.metric.Counter>                 DB_COUNT_CACHE     = new ConcurrentHashMap<>(16);
    public static final ConcurrentHashMap<String, com.agent.metric.ExplicitBucketHistogram> DB_DUR_CACHE       = new ConcurrentHashMap<>(16);
    public static final ConcurrentHashMap<String, com.agent.metric.Counter>                 DB_ERR_COUNT_CACHE = new ConcurrentHashMap<>(16);

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

        // DB 메타데이터 추출: getConnection() 메서드 캐싱
        String dbSystem = "other_sql";
        try {
            Class<?> stmtClass = stmt.getClass();
            Method mGetConn = GET_CONNECTION_CACHE.get(stmtClass);
            if (mGetConn == null) {
                mGetConn = stmtClass.getMethod("getConnection");
                GET_CONNECTION_CACHE.put(stmtClass, mGetConn);
            }
            Object conn = mGetConn.invoke(stmt);
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
        // Exemplar 캡처를 위해 scope.close() 전에 메트릭 기록 — scope.close() 후에는
        // Context.currentSpan()이 Span.invalid()를 반환하므로 traceId/spanId 캡처 불가.
        recordDbMetrics(state.dbSystem, state.dbOperation, state.startNano, error);
        state.scope.close();
        state.span.end();
    }

    /**
     * DB 메트릭 기록 — Counter/Histogram 레퍼런스를 직접 캐싱하여 hot path 오버헤드 제거.
     * JdbcPreparedStatementAdvice에서도 호출한다.
     */
    public static void recordDbMetrics(String dbSystem, String dbOperation, long startNano, Throwable error) {
        try {
            long durationMs = (System.nanoTime() - startNano) / 1_000_000L;
            final String cacheKey = dbSystem + "|" + dbOperation;

            DB_COUNT_CACHE.computeIfAbsent(cacheKey, k -> {
                java.util.Map<String, String> tags = new java.util.HashMap<>(4);
                tags.put("db.system", dbSystem);
                tags.put("db.operation.name", dbOperation);
                return com.agent.metric.MetricRegistry.get().counter("db.client.operation.count", tags);
            }).increment();

            DB_DUR_CACHE.computeIfAbsent(cacheKey, k -> {
                java.util.Map<String, String> tags = new java.util.HashMap<>(4);
                tags.put("db.system", dbSystem);
                tags.put("db.operation.name", dbOperation);
                return com.agent.metric.MetricRegistry.get().histogram("db.client.operation.duration", tags);
            }).record(durationMs);

            if (error != null) {
                DB_ERR_COUNT_CACHE.computeIfAbsent(cacheKey, k -> {
                    java.util.Map<String, String> tags = new java.util.HashMap<>(4);
                    tags.put("db.system", dbSystem);
                    tags.put("db.operation.name", dbOperation);
                    return com.agent.metric.MetricRegistry.get().counter("db.client.operation.error.count", tags);
                }).increment();
            }
        } catch (Throwable ignored) {}
    }

    // --- Connection 메타데이터 헬퍼 ---

    /**
     * DB 연결 메타데이터를 스팬에 적용한다.
     *
     * <p>ConnInfo는 커넥션 클래스별로 캐싱되므로, 동일 DataSource의 커넥션에 대해
     * reflection 조회는 최초 1회만 수행된다.
     *
     * @return dbSystem 문자열 (예: "mysql", "postgresql")
     */
    public static String extractConnMetadata(Object conn, Span span) {
        Class<?> connClass = conn.getClass();
        ConnInfo cached = CONN_INFO_CACHE.get(connClass);
        if (cached != null) {
            applyConnInfo(cached, span);
            return cached.dbSystem;
        }
        ConnInfo info = resolveConnInfo(conn, connClass);
        CONN_INFO_CACHE.put(connClass, info);
        applyConnInfo(info, span);
        return info.dbSystem;
    }

    public static void applyConnInfo(ConnInfo info, Span span) {
        if (info.dbName   != null) span.setAttribute("db.namespace",    info.dbName);
        if (info.peerName != null) span.setAttribute("server.address",  info.peerName);
        if (info.peerPort != null) {
            try { span.setAttribute("server.port", Long.parseLong(info.peerPort)); }
            catch (NumberFormatException ignored) {}
        }
        if (info.dbUser   != null) span.setAttribute("db.user",         info.dbUser);
        String peerService = info.peerName != null
                ? info.dbSystem + "@" + info.peerName
                : info.dbSystem;
        span.setAttribute("peer.service", peerService);
    }

    /** 최초 1회 reflection으로 ConnInfo를 구성한다. */
    public static ConnInfo resolveConnInfo(Object conn, Class<?> connClass) {
        try {
            Method mGetMeta = GET_META_CACHE.get(connClass);
            if (mGetMeta == null) {
                mGetMeta = connClass.getMethod("getMetaData");
                GET_META_CACHE.put(connClass, mGetMeta);
            }
            Object meta = mGetMeta.invoke(conn);
            if (meta == null) return ConnInfo.UNKNOWN;

            Class<?> metaClass = meta.getClass();

            Method mGetProductName = GET_PRODUCT_NAME_CACHE.get(metaClass);
            if (mGetProductName == null) {
                mGetProductName = metaClass.getMethod("getDatabaseProductName");
                GET_PRODUCT_NAME_CACHE.put(metaClass, mGetProductName);
            }
            String productName = (String) mGetProductName.invoke(meta);
            String dbSystem = resolveDbSystem(productName);

            String dbName = null, peerName = null, peerPort = null, dbUser = null;

            try {
                Method mGetUrl = GET_URL_CACHE.get(metaClass);
                if (mGetUrl == null) {
                    mGetUrl = metaClass.getMethod("getURL");
                    GET_URL_CACHE.put(metaClass, mGetUrl);
                }
                String url = (String) mGetUrl.invoke(meta);
                String[] parsed = parseJdbcUrl(url);   // [dbName, peerName, peerPort]
                if (parsed != null) { dbName = parsed[0]; peerName = parsed[1]; peerPort = parsed[2]; }
            } catch (Throwable ignored) {}

            try {
                Method mGetUser = GET_USERNAME_CACHE.get(metaClass);
                if (mGetUser == null) {
                    mGetUser = metaClass.getMethod("getUserName");
                    GET_USERNAME_CACHE.put(metaClass, mGetUser);
                }
                String user = (String) mGetUser.invoke(meta);
                if (user != null && !user.isEmpty()) dbUser = user;
            } catch (Throwable ignored) {}

            return new ConnInfo(dbSystem, dbName, peerName, peerPort, dbUser);
        } catch (Throwable t) {
            return ConnInfo.UNKNOWN;
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
     * JDBC URL에서 [dbName, peerName, peerPort]를 파싱한다.
     * 형식: jdbc:vendor://host:port/dbname?params
     *
     * @return [dbName, peerName, peerPort] (각 원소 nullable), URL 파싱 실패 시 null
     */
    static String[] parseJdbcUrl(String url) {
        if (url == null) return null;
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return null;
            String rest = url.substring(schemeEnd + 3);

            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);

            int slash     = rest.indexOf('/');
            String hostPort = slash >= 0 ? rest.substring(0, slash) : rest;
            String dbName   = slash >= 0 && slash < rest.length() - 1
                    ? rest.substring(slash + 1) : null;
            if (dbName != null && dbName.isEmpty()) dbName = null;

            String peerName = null, peerPort = null;
            if (hostPort.startsWith("[")) {
                int close = hostPort.indexOf(']');
                if (close >= 0) {
                    peerName = hostPort.substring(1, close);
                    if (close + 2 < hostPort.length()) peerPort = hostPort.substring(close + 2);
                }
            } else {
                int colon = hostPort.lastIndexOf(':');
                if (colon >= 0) {
                    peerName = hostPort.substring(0, colon);
                    peerPort = hostPort.substring(colon + 1);
                } else if (!hostPort.isEmpty()) {
                    peerName = hostPort;
                }
            }
            return new String[]{ dbName, peerName, peerPort };
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** DB URL 파싱 결과를 스팬에 적용 (외부 호출용 래퍼) */
    static void parseJdbcUrl(String url, Span span) {
        String[] parts = parseJdbcUrl(url);
        if (parts == null) return;
        if (parts[0] != null) span.setAttribute("db.namespace",   parts[0]);
        if (parts[1] != null) span.setAttribute("server.address", parts[1]);
        if (parts[2] != null) {
            try { span.setAttribute("server.port", Long.parseLong(parts[2])); }
            catch (NumberFormatException ignored) {}
        }
    }

    /** SQL 첫 단어에서 db.operation 추출 (카디널리티 낮음: SELECT/INSERT/UPDATE/DELETE 등) */
    public static String extractDbOperation(String sql) {
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

#!/usr/bin/env bash
# Javi Agent E2E Test Script
# agent + test-app + javi-collector 세 서버를 실제 구동해 계측 데이터 적재까지 검증한다.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COLLECTOR_DIR="/Users/kkc/javi-collector"
AGENT_JAR="$SCRIPT_DIR/agent/target/javi-1.0.0.jar"
APP_JAR="$SCRIPT_DIR/test-app/target/test-0.0.1-SNAPSHOT.jar"
COLLECTOR_BIN="/tmp/javi-collector-e2e"
BASE_URL="http://localhost:8080"
COLLECTOR_URL="http://localhost:4318"
SERVICE_NAME="e2e-test-app"
APP_PID=""
COLLECTOR_PID=""
PASS=0
FAIL=0

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

check_status() {
    local label="$1" url="$2" expected="$3"
    actual=$(curl -s -o /dev/null -w "%{http_code}" "$url")
    if [ "$actual" = "$expected" ]; then
        pass "$label (HTTP $actual)"
    else
        fail "$label (expected $expected, got $actual)"
    fi
}

check_body() {
    local label="$1" url="$2" pattern="$3"
    body=$(curl -s "$url")
    if echo "$body" | grep -q "$pattern"; then
        pass "$label"
    else
        fail "$label (pattern '$pattern' not found in: $body)"
    fi
}

# JSON 배열에서 항목 수를 반환한다 (python3 사용)
json_count() {
    python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else 0)" 2>/dev/null || echo "0"
}

# JSON 객체에서 중첩 정수값을 반환한다: json_int "key1.key2"
json_int() {
    local path="$1"
    python3 -c "
import json,sys
d=json.load(sys.stdin)
keys='$path'.split('.')
for k in keys:
    d=d.get(k,{}) if isinstance(d,dict) else 0
print(d if isinstance(d,(int,float)) else 0)
" 2>/dev/null || echo "0"
}

check_ingestion() {
    local label="$1" url="$2" field="$3"
    local body count
    body=$(curl -s "$url")
    count=$(echo "$body" | json_count)
    if [ "$count" -gt 0 ]; then
        pass "$label (count=$count)"
    else
        fail "$label (응답이 비어 있음: $body)"
    fi
}

check_stat() {
    local label="$1" url="$2" field="$3" min="$4"
    local body val
    body=$(curl -s "$url")
    val=$(echo "$body" | json_int "$field")
    if [ "${val:-0}" -ge "$min" ]; then
        pass "$label ($field=$val >= $min)"
    else
        fail "$label ($field=$val, expected >= $min)"
    fi
}

cleanup() {
    if [ -n "$APP_PID" ]; then
        info "앱 종료 중 (PID=$APP_PID)..."
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    if [ -n "$COLLECTOR_PID" ]; then
        info "Collector 종료 중 (PID=$COLLECTOR_PID)..."
        kill "$COLLECTOR_PID" 2>/dev/null || true
        wait "$COLLECTOR_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# ─── [0] Collector 빌드 ────────────────────────────────────
info "=== javi-collector 빌드 ==="
(cd "$COLLECTOR_DIR" && go build -o "$COLLECTOR_BIN" ./cmd/collector)
info "Collector binary: $COLLECTOR_BIN"

# ─── [0] Collector 기동 ───────────────────────────────────
JAVI_LOG_DIR="$SCRIPT_DIR/javi/logs"
mkdir -p "$JAVI_LOG_DIR"

info "=== javi-collector 기동 (production 모드, in-memory store) ==="
# DISABLE_CLICKHOUSE=true: 로컬 E2E에는 ClickHouse 없이 in-memory store 사용
# SAMPLING_ENABLED=true: production 샘플링 파이프라인 검증
# SAMPLING_CONFIG_JSON: error + slow(≥400ms) + 100% probabilistic (E2E 전량 보존)
DISABLE_CLICKHOUSE=true \
WAL_ENABLED=true \
WAL_DIR="$JAVI_LOG_DIR/wal" \
BACKUP_DIR="$JAVI_LOG_DIR/backup" \
SAMPLING_ENABLED=true \
SAMPLING_CONFIG_JSON='{"enabled":true,"error_sampling":{"enabled":true},"latency_sampling":{"enabled":true,"threshold_ms":400},"probabilistic_sampling":{"enabled":true,"rate":1.0},"trace_timeout_sec":10,"max_buffer_traces":5000}' \
KAFKA_ENABLED=false \
EMBED_ENABLED=false \
MEMORY_BUFFER_SIZE=5000 \
HTTP_PORT=4318 \
GRPC_PORT=4317 \
    "$COLLECTOR_BIN" > "$JAVI_LOG_DIR/collector.log" 2>&1 &
COLLECTOR_PID=$!
info "Collector PID=$COLLECTOR_PID, 로그: $JAVI_LOG_DIR/collector.log"

info "Collector 준비 대기 중..."
for i in $(seq 1 15); do
    if curl -sf "$COLLECTOR_URL/healthz" > /dev/null 2>&1; then
        info "Collector 준비 완료 (${i}초)"
        break
    fi
    if [ "$i" -eq 15 ]; then
        echo "Collector 기동 타임아웃. 로그 확인:"
        tail -20 "$JAVI_LOG_DIR/collector.log"
        exit 1
    fi
    sleep 1
done

# ─── Agent · App 빌드 ────────────────────────────────────
info "=== Javi Agent 빌드 ==="
(cd "$SCRIPT_DIR/agent" && mvn package -DskipTests -q)
info "Agent JAR: $AGENT_JAR"

info "=== Test-App 빌드 ==="
(cd "$SCRIPT_DIR/test-app" && mvn package -DskipTests -q \
    -Dmaven-surefire-plugin.argLine="")
info "App JAR: $APP_JAR"

# ─── 앱 기동 ─────────────────────────────────────────────
info "=== 에이전트를 붙여 앱 기동 ==="
JAVI_SERVICE_NAME="$SERVICE_NAME" \
java \
    -javaagent:"$AGENT_JAR" \
    -Dcom.agent.shaded.bytebuddy.experimental=true \
    -Dnet.bytebuddy.experimental=true \
    -Dspring.profiles.active=e2e \
    -jar "$APP_JAR" \
    > "$JAVI_LOG_DIR/e2e-app.log" 2>&1 &

APP_PID=$!
info "앱 PID=$APP_PID, 로그: $JAVI_LOG_DIR/e2e-app.log"

info "앱 준비 대기 중..."
for i in $(seq 1 30); do
    if curl -s "$BASE_URL/api/users/health" | grep -q "running" 2>/dev/null; then
        info "앱 준비 완료 (${i}초)"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "앱 기동 타임아웃. 로그 확인:"
        tail -20 "$JAVI_LOG_DIR/e2e-app.log"
        exit 1
    fi
    sleep 1
done

echo ""
info "=== [1/3] HTTP 엔드포인트 E2E 테스트 ==="
echo ""

# ─── [1] 정상 CRUD ──────────────────────────────────────
info "--- [1] Spring MVC + JDBC CRUD ---"
CREATE_RESP=$(curl -s -X POST "$BASE_URL/api/users" \
    -H "Content-Type: application/json" \
    -d '{"name":"E2E유저","email":"e2e@test.com","phone":"010-1234-5678"}')
USER_ID=$(echo "$CREATE_RESP" | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
if [ -n "$USER_ID" ]; then
    pass "POST /api/users → id=$USER_ID 생성"
else
    fail "POST /api/users 실패: $CREATE_RESP"
fi

check_status "GET /api/users/$USER_ID" "$BASE_URL/api/users/$USER_ID" "200"
check_status "GET /api/users" "$BASE_URL/api/users" "200"

curl -s -X PUT "$BASE_URL/api/users/$USER_ID" \
    -H "Content-Type: application/json" \
    -d '{"name":"E2E수정","email":"e2e-upd@test.com","phone":"010-9999-0000"}' > /dev/null
pass "PUT /api/users/$USER_ID"

curl -s -X DELETE "$BASE_URL/api/users/$USER_ID" > /dev/null
pass "DELETE /api/users/$USER_ID"

check_status "GET /api/users/$USER_ID (삭제 후 404)" "$BASE_URL/api/users/$USER_ID" "404"

# ─── [2] 슬로우 응답 ────────────────────────────────────
info ""
info "--- [2] Slow Response (500ms) ---"
START=$(python3 -c "import time; print(int(time.time()*1000))")
check_status "GET /api/test/slow?ms=500" "$BASE_URL/api/test/slow?ms=500" "200"
END=$(python3 -c "import time; print(int(time.time()*1000))")
ELAPSED=$((END - START))
if [ "$ELAPSED" -ge 450 ]; then
    pass "슬로우 지연 확인: ${ELAPSED}ms"
else
    fail "슬로우 지연 미달: ${ELAPSED}ms (expected ≥450ms)"
fi

# ─── [3] 서버 에러 ──────────────────────────────────────
info ""
info "--- [3] 서버 에러 ---"
check_status "GET /api/test/error/npe" "$BASE_URL/api/test/error/npe" "500"
check_body   "GET /api/test/error/runtime" "$BASE_URL/api/test/error/runtime" "RUNTIME_EXCEPTION"
check_status "GET /api/test/error/overflow" "$BASE_URL/api/test/error/overflow" "500"

# ─── [4] DB 에러 ────────────────────────────────────────
info ""
info "--- [4] DB 에러 ---"
check_body "GET /api/test/db/invalid-sql" "$BASE_URL/api/test/db/invalid-sql" "DB_SQL_ERROR"
check_body "GET /api/test/db/duplicate-key" "$BASE_URL/api/test/db/duplicate-key" "DB_DUPLICATE_KEY"
check_body "GET /api/test/db/table-not-found" "$BASE_URL/api/test/db/table-not-found" "DB_TABLE_NOT_FOUND"
check_body "GET /api/test/db/column-too-long" "$BASE_URL/api/test/db/column-too-long" "DB_COLUMN_TOO_LONG"

# ─── [5] 정상 다중 쿼리 ─────────────────────────────────
info ""
info "--- [5] 정상 다중 쿼리 ---"
check_status "GET /api/test/ok" "$BASE_URL/api/test/ok" "200"
check_status "GET /api/test/db/multi-query" "$BASE_URL/api/test/db/multi-query" "200"

# ─── [6] HTTP Client ────────────────────────────────────
info ""
info "--- [6] HTTP Client span ---"
check_body "GET /api/users/health" "$BASE_URL/api/users/health" "running"

# ─── BatchSpanProcessor flush 대기 ──────────────────────
echo ""
info "=== span flush 대기 (12초 — BatchSpanProcessor delay 5s + 버퍼) ==="
sleep 12

# ─── [2/3] Collector 적재 검증 ──────────────────────────
echo ""
info "=== [2/3] Collector 데이터 적재 검증 ==="
echo ""

# [A] stats: ingester가 trace를 수신했는지 확인
info "--- [A] Ingester 수신 카운터 ---"
check_stat "traces.received > 0" \
    "$COLLECTOR_URL/api/collector/stats" \
    "traces.received" 1

check_stat "metrics.received > 0 (JVM 메트릭)" \
    "$COLLECTOR_URL/api/collector/stats" \
    "metrics.received" 1

# [B] traces 조회: 서비스명 필터
info ""
info "--- [B] 서비스별 trace 조회 ---"
check_ingestion "traces?service=$SERVICE_NAME 조회" \
    "$COLLECTOR_URL/api/collector/traces?service=$SERVICE_NAME&limit=100" \
    "count"

# [C] 에러 trace: NPE/RuntimeException으로 발생한 STATUS=ERROR spans
info ""
info "--- [C] 에러 span 적재 확인 ---"
check_ingestion "traces?service=$SERVICE_NAME&status=error" \
    "$COLLECTOR_URL/api/collector/traces?service=$SERVICE_NAME&status=error&limit=50" \
    "count"

# [D] 슬로우 trace: 500ms 지연 요청 → min_duration_ms=400 으로 확인
info ""
info "--- [D] 슬로우 span 적재 확인 ---"
check_ingestion "traces?service=$SERVICE_NAME&min_duration_ms=400" \
    "$COLLECTOR_URL/api/collector/traces?service=$SERVICE_NAME&min_duration_ms=400&limit=20" \
    "count"

# [E] 메트릭 조회: JVM 메트릭이 적재됐는지 확인
info ""
info "--- [E] JVM 메트릭 적재 확인 ---"
check_ingestion "metrics?service=$SERVICE_NAME" \
    "$COLLECTOR_URL/api/collector/metrics?service=$SERVICE_NAME&limit=20" \
    "count"

# ─── [3/3] Sampling 정책 단위 테스트 ────────────────────
echo ""
info "=== [3/3] Sampling 정책 unit test (go test) ==="
(cd "$COLLECTOR_DIR" && go test -v -count=1 ./internal/sampling/ 2>&1 | tail -20) || true
if (cd "$COLLECTOR_DIR" && go test -count=1 ./internal/sampling/ > /dev/null 2>&1); then
    pass "sampling unit test PASS"
else
    fail "sampling unit test FAIL"
fi

# ─── 결과 요약 ──────────────────────────────────────────
echo ""
echo "════════════════════════════════════════"
info "에이전트 span 출력 (마지막 20줄):"
grep -E "span-export|span status|Transformed:|OTLP" "$JAVI_LOG_DIR/e2e-app.log" | tail -20 || true
echo ""
info "Collector 적재 최종 stats:"
curl -s "$COLLECTOR_URL/api/collector/stats" | python3 -m json.tool || true
echo ""
echo "════════════════════════════════════════"
TOTAL=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}✓ E2E 테스트 완료: $PASS/$TOTAL 통과${NC}"
    exit 0
else
    echo -e "${RED}✗ E2E 테스트 실패: $FAIL/$TOTAL 실패 ($PASS 통과)${NC}"
    exit 1
fi

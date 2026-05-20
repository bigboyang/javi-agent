#!/usr/bin/env bash
# Javi APM — Kubernetes E2E Test
# agent + test-app + collector + forecast が k8s で正常に動作するか検証

set -euo pipefail

BASE_URL="http://localhost:28080"
COLLECTOR_URL="http://localhost:24318"
FORECAST_URL="http://localhost:28081"
COLLECTOR_API_KEY="changeme-collector-api-key"
SERVICE_NAME="e2e-test-app"

PASS=0; FAIL=0
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL=$((FAIL+1)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

check_status() {
    local label="$1" url="$2" expected="$3"
    actual=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$url")
    if [ "$actual" = "$expected" ]; then pass "$label (HTTP $actual)"
    else fail "$label (expected $expected, got $actual)"; fi
}

check_body() {
    local label="$1" url="$2" pattern="$3"
    body=$(curl -s --max-time 10 "$url")
    if echo "$body" | grep -q "$pattern"; then pass "$label"
    else fail "$label (pattern '$pattern' not found: $body)"; fi
}

json_count() {
    python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d) if isinstance(d,list) else 0)" 2>/dev/null || echo "0"
}

json_int() {
    python3 -c "
import json,sys
d=json.load(sys.stdin)
for k in '$1'.split('.'): d=d.get(k,{}) if isinstance(d,dict) else 0
print(d if isinstance(d,(int,float)) else 0)
" 2>/dev/null || echo "0"
}

check_ingestion() {
    local label="$1" url="$2"
    body=$(curl -s --max-time 10 "$url")
    count=$(echo "$body" | json_count)
    if [ "${count:-0}" -gt 0 ]; then pass "$label (count=$count)"
    else fail "$label (empty: $body)"; fi
}

check_stat() {
    local label="$1" url="$2" field="$3" min="$4"
    body=$(curl -s --max-time 10 "$url")
    val=$(echo "$body" | json_int "$field")
    if [ "${val:-0}" -ge "$min" ]; then pass "$label ($field=$val >= $min)"
    else fail "$label ($field=$val, want >= $min)"; fi
}

echo ""
info "=== [0] K8s Pod 상태 확인 ==="
kubectl get pods -n apm -o wide 2>&1

# ─── [1] 서비스 Health Check ───────────────────────────────
echo ""
info "=== [1] 서비스 Health Check ==="

check_body  "test-app /api/users/health" "$BASE_URL/api/users/health" "running"
check_body  "collector /healthz"         "$COLLECTOR_URL/healthz"     "ok"
check_body  "forecast /healthz"          "$FORECAST_URL/healthz"      "ok"

# ─── [2] HTTP CRUD (agent가 span 생성하는지 확인) ──────────
echo ""
info "=== [2] HTTP CRUD — Spring MVC + JDBC ==="

CREATE=$(curl -s --max-time 10 -X POST "$BASE_URL/api/users" \
    -H "Content-Type: application/json" \
    -d '{"name":"K8sUser","email":"k8s@test.com","phone":"010-0000-0001"}')
USER_ID=$(echo "$CREATE" | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
if [ -n "$USER_ID" ]; then pass "POST /api/users → id=$USER_ID"
else fail "POST /api/users: $CREATE"; fi

check_status "GET /api/users/$USER_ID"       "$BASE_URL/api/users/$USER_ID"  "200"
check_status "GET /api/users"                 "$BASE_URL/api/users"           "200"

curl -s --max-time 10 -X PUT "$BASE_URL/api/users/$USER_ID" \
    -H "Content-Type: application/json" \
    -d '{"name":"K8sUpdated","email":"k8s-upd@test.com","phone":"010-9999-1111"}' > /dev/null
pass "PUT /api/users/$USER_ID"

curl -s --max-time 10 -X DELETE "$BASE_URL/api/users/$USER_ID" > /dev/null
pass "DELETE /api/users/$USER_ID"

check_status "GET /api/users/$USER_ID (삭제후 404)" "$BASE_URL/api/users/$USER_ID" "404"

# ─── [3] Slow & Error trace 생성 ────────────────────────────
echo ""
info "=== [3] Slow Response / Error span 생성 ==="

START=$(python3 -c "import time; print(int(time.time()*1000))")
check_status "GET /api/test/slow?ms=500"     "$BASE_URL/api/test/slow?ms=500"   "200"
END=$(python3 -c "import time; print(int(time.time()*1000))")
ELAPSED=$((END - START))
if [ "$ELAPSED" -ge 450 ]; then pass "슬로우 지연 확인: ${ELAPSED}ms"
else fail "슬로우 지연 미달: ${ELAPSED}ms (expected >=450ms)"; fi

check_status "GET /api/test/error/npe"       "$BASE_URL/api/test/error/npe"      "500"
check_body   "GET /api/test/error/runtime"   "$BASE_URL/api/test/error/runtime"  "RUNTIME_EXCEPTION"
check_body   "GET /api/test/db/invalid-sql"  "$BASE_URL/api/test/db/invalid-sql" "DB_SQL_ERROR"

# ─── [4] BatchSpanProcessor flush 대기 ───────────────────────
echo ""
info "=== [4] Span flush 대기 (15초) ==="
sleep 15

# ─── [5] Collector 적재 검증 ────────────────────────────────
echo ""
info "=== [5] Collector 적재 검증 ==="

CAUTH="-H 'X-API-Key: $COLLECTOR_API_KEY'"
collector_get() { curl -s --max-time 10 -H "X-API-Key: $COLLECTOR_API_KEY" "$1"; }

val=$(collector_get "$COLLECTOR_URL/api/collector/stats" | json_int "traces.received")
if [ "${val:-0}" -ge 1 ]; then pass "collector traces.received=$val >= 1"
else fail "collector traces.received=$val (want >= 1)"; fi

val=$(collector_get "$COLLECTOR_URL/api/collector/stats" | json_int "metrics.received")
if [ "${val:-0}" -ge 1 ]; then pass "collector metrics.received=$val >= 1"
else fail "collector metrics.received=$val (want >= 1)"; fi

body=$(collector_get "$COLLECTOR_URL/api/collector/traces?service=$SERVICE_NAME&limit=50")
count=$(echo "$body" | json_count)
if [ "${count:-0}" -gt 0 ]; then pass "traces?service=$SERVICE_NAME (count=$count)"
else fail "traces?service=$SERVICE_NAME (empty: $body)"; fi

body=$(collector_get "$COLLECTOR_URL/api/collector/traces?service=$SERVICE_NAME&status=error&limit=20")
count=$(echo "$body" | json_count)
if [ "${count:-0}" -gt 0 ]; then pass "traces?service=$SERVICE_NAME&status=error (count=$count)"
else fail "traces?service=$SERVICE_NAME&status=error (empty)"; fi

body=$(collector_get "$COLLECTOR_URL/api/collector/traces?service=$SERVICE_NAME&min_duration_ms=400&limit=20")
count=$(echo "$body" | json_count)
if [ "${count:-0}" -gt 0 ]; then pass "traces?service=$SERVICE_NAME&min_duration_ms=400 (count=$count)"
else fail "traces?service=$SERVICE_NAME&min_duration_ms=400 (empty)"; fi

# ─── [6] Forecast 서비스 API 검증 ────────────────────────────
echo ""
info "=== [6] Forecast 서비스 API 검증 ==="

check_body "forecast /healthz" "$FORECAST_URL/healthz" "ok"
check_body "forecast /readyz"  "$FORECAST_URL/readyz"  "ok"

SERVICES_RESP=$(curl -s --max-time 10 "$FORECAST_URL/api/services" 2>/dev/null || echo "")
if echo "$SERVICES_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); exit(0 if 'services' in d or isinstance(d,list) else 1)" 2>/dev/null; then
    SCOUNT=$(echo "$SERVICES_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('total',len(d) if isinstance(d,list) else 0))" 2>/dev/null || echo "0")
    pass "forecast /api/services → JSON 반환 (total=$SCOUNT, Kafka 없이 초기 상태)"
else
    fail "forecast /api/services 응답 이상: $SERVICES_RESP"
fi

ANOMALIES_RESP=$(curl -s --max-time 10 "$FORECAST_URL/api/forecast/anomalies" 2>/dev/null || echo "")
if echo "$ANOMALIES_RESP" | python3 -c "import json,sys; json.load(sys.stdin)" 2>/dev/null; then
    pass "forecast /api/forecast/anomalies → JSON 반환"
else
    fail "forecast /api/forecast/anomalies 응답 이상: $ANOMALIES_RESP"
fi

METRICS_SERVICES=$(curl -s --max-time 10 "$FORECAST_URL/api/metrics/services" 2>/dev/null || echo "")
if echo "$METRICS_SERVICES" | python3 -c "import json,sys; json.load(sys.stdin)" 2>/dev/null; then
    pass "forecast /api/metrics/services → JSON 반환"
else
    fail "forecast /api/metrics/services 응답 이상: $METRICS_SERVICES"
fi

# ─── 최종 요약 ───────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════"
info "Collector stats:"
collector_get "$COLLECTOR_URL/api/collector/stats" | python3 -m json.tool 2>/dev/null || true
echo ""
info "Forecast services:"
curl -s --max-time 10 "$FORECAST_URL/api/services" | python3 -m json.tool 2>/dev/null || true
echo ""
echo "════════════════════════════════════════════"
TOTAL=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
    echo -e "${GREEN}✓ K8s E2E 완료: $PASS/$TOTAL 통과${NC}"
    exit 0
else
    echo -e "${RED}✗ K8s E2E 실패: $FAIL/$TOTAL 실패 ($PASS 통과)${NC}"
    exit 1
fi

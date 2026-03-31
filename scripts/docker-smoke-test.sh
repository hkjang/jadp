#!/usr/bin/env bash
# =============================================================================
# JADP Docker Smoke Test
# Validates that a deployed JADP Docker environment is functioning correctly.
# Run after docker-run.sh completes.
# Usage: ./scripts/docker-smoke-test.sh [APP_URL] [HYBRID_URL]
# =============================================================================
set -euo pipefail

APP_URL="${1:-http://localhost:${APP_PORT:-8080}}"
HYBRID_URL="${2:-http://localhost:${HYBRID_PORT:-5002}}"
TIMEOUT_SEC="${SMOKE_TIMEOUT_SEC:-10}"

PASS=0
FAIL=0
SKIP=0
TOTAL=0

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
green()  { printf '\033[0;32m%s\033[0m' "$*"; }
red()    { printf '\033[0;31m%s\033[0m' "$*"; }
yellow() { printf '\033[0;33m%s\033[0m' "$*"; }

record_pass() { ((TOTAL++)); ((PASS++));  echo "  $(green PASS) $1"; }
record_fail() { ((TOTAL++)); ((FAIL++));  echo "  $(red FAIL) $1"; [ -n "${2:-}" ] && echo "        hint: $2"; }
record_skip() { ((TOTAL++)); ((SKIP++));  echo "  $(yellow SKIP) $1"; }

http_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time "${TIMEOUT_SEC}" "$1" 2>/dev/null || echo "000"
}

http_body() {
  curl -s --max-time "${TIMEOUT_SEC}" "$1" 2>/dev/null || echo ""
}

# ---------------------------------------------------------------------------
# 1. Health Check
# ---------------------------------------------------------------------------
echo ""
echo "=== JADP Smoke Test ==="
echo "App URL    : ${APP_URL}"
echo "Hybrid URL : ${HYBRID_URL}"
echo ""

echo "[1/8] Actuator Health"
HEALTH_BODY="$(http_body "${APP_URL}/actuator/health")"
HEALTH_STATUS="$(echo "${HEALTH_BODY}" | grep -o '"status":"[A-Z]*"' | head -1 || true)"
if echo "${HEALTH_STATUS}" | grep -q '"UP"'; then
  record_pass "GET /actuator/health -> UP"
else
  record_fail "GET /actuator/health" "Response: ${HEALTH_BODY:0:200}"
fi

# ---------------------------------------------------------------------------
# 2. Test Page
# ---------------------------------------------------------------------------
echo "[2/8] Test Console Page"
STATUS="$(http_status "${APP_URL}/")"
if [ "${STATUS}" = "200" ]; then
  record_pass "GET / -> 200"
else
  record_fail "GET / -> ${STATUS}" "Test console page not reachable"
fi

# ---------------------------------------------------------------------------
# 3. Swagger UI
# ---------------------------------------------------------------------------
echo "[3/8] Swagger UI"
STATUS="$(http_status "${APP_URL}/swagger-ui.html")"
# swagger-ui.html redirects to /swagger-ui/index.html (302 or 200)
if [ "${STATUS}" = "200" ] || [ "${STATUS}" = "302" ]; then
  record_pass "GET /swagger-ui.html -> ${STATUS}"
else
  record_fail "GET /swagger-ui.html -> ${STATUS}" "OpenAPI docs not available"
fi

# ---------------------------------------------------------------------------
# 4. Config Options API
# ---------------------------------------------------------------------------
echo "[4/8] PDF Config Options API"
OPTIONS_BODY="$(http_body "${APP_URL}/api/v1/pdf/config/options")"
if echo "${OPTIONS_BODY}" | grep -q '"formats"'; then
  record_pass "GET /api/v1/pdf/config/options -> valid JSON"
else
  record_fail "GET /api/v1/pdf/config/options" "Expected JSON with 'formats' field"
fi

# ---------------------------------------------------------------------------
# 5. Hybrid Service
# ---------------------------------------------------------------------------
echo "[5/8] Hybrid Service Connectivity"
HYBRID_STATUS="$(http_status "${HYBRID_URL}/docs")"
if [ "${HYBRID_STATUS}" = "200" ]; then
  record_pass "GET ${HYBRID_URL}/docs -> 200"
else
  record_skip "Hybrid service not reachable (status=${HYBRID_STATUS}) - may be disabled"
fi

# ---------------------------------------------------------------------------
# 6. PDF Convert (Image Upload)
# ---------------------------------------------------------------------------
echo "[6/8] PDF Convert API (image upload)"
# Create a minimal 1x1 red PNG (68 bytes)
TEST_PNG="$(mktemp /tmp/jadp-smoke-XXXXXX.png)"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82' > "${TEST_PNG}"

CONVERT_RESPONSE="$(curl -s --max-time 60 \
  -F "file=@${TEST_PNG};filename=test.png" \
  -F "formats=json" \
  "${APP_URL}/api/v1/pdf/convert-sync" 2>/dev/null || echo '{"error":"timeout"}')"
rm -f "${TEST_PNG}"

if echo "${CONVERT_RESPONSE}" | grep -q '"status"'; then
  CONV_STATUS="$(echo "${CONVERT_RESPONSE}" | grep -o '"status":"[A-Z]*"' | head -1 || true)"
  if echo "${CONV_STATUS}" | grep -q '"SUCCEEDED"'; then
    record_pass "POST /api/v1/pdf/convert-sync -> SUCCEEDED"
  elif echo "${CONV_STATUS}" | grep -q '"FAILED"'; then
    CONV_ERROR="$(echo "${CONVERT_RESPONSE}" | grep -o '"error":"[^"]*"' | head -1 || true)"
    record_fail "POST /api/v1/pdf/convert-sync -> FAILED" "${CONV_ERROR}"
  else
    record_fail "POST /api/v1/pdf/convert-sync -> ${CONV_STATUS}" "Unexpected status"
  fi
else
  record_fail "POST /api/v1/pdf/convert-sync" "No valid response: ${CONVERT_RESPONSE:0:200}"
fi

# ---------------------------------------------------------------------------
# 7. PII Detect API
# ---------------------------------------------------------------------------
echo "[7/8] PII Detect API"
TEST_PNG2="$(mktemp /tmp/jadp-smoke-pii-XXXXXX.png)"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82' > "${TEST_PNG2}"

PII_RESPONSE="$(curl -s --max-time 60 \
  -F "file=@${TEST_PNG2};filename=test.png" \
  "${APP_URL}/api/v1/pii/detect" 2>/dev/null || echo '{"error":"timeout"}')"
rm -f "${TEST_PNG2}"

PII_STATUS="$(echo "${PII_RESPONSE}" | grep -o '"findingCount"' || true)"
if [ -n "${PII_STATUS}" ]; then
  record_pass "POST /api/v1/pii/detect -> valid response"
else
  # Check if it's a known error
  PII_ERR="$(echo "${PII_RESPONSE}" | grep -o '"message":"[^"]*"' | head -1 || true)"
  if [ -n "${PII_ERR}" ]; then
    record_fail "POST /api/v1/pii/detect" "${PII_ERR}"
  else
    record_fail "POST /api/v1/pii/detect" "Unexpected: ${PII_RESPONSE:0:200}"
  fi
fi

# ---------------------------------------------------------------------------
# 8. Korean Filename Upload
# ---------------------------------------------------------------------------
echo "[8/8] Korean Filename Upload"
TEST_KO="$(mktemp /tmp/jadp-smoke-ko-XXXXXX.png)"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82' > "${TEST_KO}"

KO_RESPONSE="$(curl -s --max-time 60 \
  -F "file=@${TEST_KO};filename=한글테스트문서.png" \
  -F "formats=json" \
  "${APP_URL}/api/v1/pdf/convert-sync" 2>/dev/null || echo '{"error":"timeout"}')"
rm -f "${TEST_KO}"

if echo "${KO_RESPONSE}" | grep -q '"sourceFilename"'; then
  KO_FILENAME="$(echo "${KO_RESPONSE}" | grep -o '"sourceFilename":"[^"]*"' | head -1 || true)"
  if echo "${KO_FILENAME}" | grep -q '한글'; then
    record_pass "Korean filename preserved: ${KO_FILENAME}"
  else
    record_fail "Korean filename lost" "Got: ${KO_FILENAME}"
  fi
else
  record_fail "Korean filename upload" "No valid response: ${KO_RESPONSE:0:200}"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "==========================================="
echo " Results: $(green "${PASS} PASS")  $(red "${FAIL} FAIL")  $(yellow "${SKIP} SKIP")  (${TOTAL} total)"
echo "==========================================="

if [ "${FAIL}" -gt 0 ]; then
  echo ""
  echo "Troubleshooting tips:"
  echo "  - Check app logs : docker logs jadp-app --tail 200"
  echo "  - Check hybrid   : docker logs jadp-hybrid --tail 200"
  echo "  - Check health   : curl -s ${APP_URL}/actuator/health | python3 -m json.tool"
  echo "  - Check storage  : docker exec jadp-app ls -la /var/app-data"
  echo ""
  exit 1
fi

exit 0

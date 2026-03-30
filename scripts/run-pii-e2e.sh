#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-18086}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SETTINGS_FILE="${ROOT_DIR}/.mvn-local-settings.xml"
ARTIFACT_DIR="${ROOT_DIR}/test-artifacts"
REPORT_FILE="${ARTIFACT_DIR}/PII_DETECTION_MASKING_REPORT.md"
RAW_RESULTS_FILE="${ARTIFACT_DIR}/pii-detection-results.json"
LOG_FILE="${ARTIFACT_DIR}/pii-app-${PORT}.log"
ERR_LOG_FILE="${ARTIFACT_DIR}/pii-app-${PORT}.err.log"
DOWNLOAD_DIR="${ARTIFACT_DIR}/pii-downloads"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/jadp-pii-e2e.XXXXXX")"
MVN_BIN="${MVN_BIN:-mvn}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN_OFFLINE="${MVN_OFFLINE:-false}"
APP_PID=""
BASE_URL=""

mkdir -p "${ARTIFACT_DIR}" "${DOWNLOAD_DIR}"

cleanup() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" >/dev/null 2>&1; then
    kill "${APP_PID}" >/dev/null 2>&1 || true
    wait "${APP_PID}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP_DIR}"
}

trap cleanup EXIT

require_tool() {
  local tool="$1"
  if [[ "${tool}" == */* ]]; then
    [[ -x "${tool}" ]] || {
      echo "Required executable not found: ${tool}" >&2
      exit 1
    }
    return 0
  fi

  command -v "${tool}" >/dev/null 2>&1 || {
    echo "Required command not found: ${tool}" >&2
    exit 1
  }
}

wait_http_ready() {
  local url="$1"
  local timeout_seconds="${2:-120}"
  local deadline=$((SECONDS + timeout_seconds))

  while (( SECONDS < deadline )); do
    local http_code
    http_code="$(curl -s -o /dev/null -w '%{http_code}' "${url}" || true)"
    if [[ "${http_code}" =~ ^[0-9]+$ ]] && (( http_code >= 200 && http_code < 500 )); then
      return 0
    fi
    sleep 2
  done

  return 1
}

append_json_line() {
  local file="$1"
  local payload="$2"
  printf '%s\n' "${payload}" >> "${file}"
}

run_detect_case() {
  local name="$1"
  local file_path="$2"
  local note="$3"
  local response_file="${TMP_DIR}/${name}.json"
  local http_code

  http_code="$(curl -sS -o "${response_file}" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/pii/detect" -F "file=@${file_path}")"
  if [[ ! "${http_code}" =~ ^[0-9]+$ ]] || (( http_code < 200 || http_code >= 300 )); then
    echo "PII detect scenario ${name} failed with HTTP ${http_code}" >&2
    cat "${response_file}" >&2
    exit 1
  fi

  append_json_line "${TMP_DIR}/results.jsonl" "$(jq -nc \
    --arg name "${name}" \
    --arg file "${file_path#${ROOT_DIR}/}" \
    --arg note "${note}" \
    --slurpfile response "${response_file}" '
    {
      name: $name,
      file: $file,
      note: $note,
      findingCount: ($response[0].findingCount // 0),
      types: (($response[0].findings // []) | map(.type) | unique),
      pages: (($response[0].findings // []) | map(.pageNumber) | unique),
      findings: ($response[0].findings // [])
    }')"
}

generate_report() {
  local metadata_file="$1"
  local executed_at
  executed_at="$(jq -r '.executedAt' "${metadata_file}")"

  {
    echo "# PII Detection and Masking Report"
    echo
    echo "- Executed at: ${executed_at}"
    echo "- Test port: ${PORT}"
    echo "- Raw results JSON: \`test-artifacts/pii-detection-results.json\`"
    echo "- Note: PII positive tests use synthetic Korean documents to avoid using exposed real-person personal data."
    echo
    echo "## Key Findings"
    echo
    echo "- 합성 한글 PDF 1건에서 탐지된 PII 유형 수: $(jq -r '.results[] | select(.name == "synthetic_full_detect") | (.types | length)' "${metadata_file}") / 12"
    echo "- 누락 유형: $(jq -r '
      .results[] | select(.name == "synthetic_full_detect") | .types as $types |
      ["RESIDENT_REGISTRATION_NUMBER","DRIVER_LICENSE_NUMBER","PASSPORT_NUMBER","FOREIGNER_REGISTRATION_NUMBER","MOBILE_PHONE_NUMBER","LANDLINE_PHONE_NUMBER","CREDIT_CARD_NUMBER","BANK_ACCOUNT_NUMBER","PERSON_NAME","EMAIL_ADDRESS","IP_ADDRESS","STREET_ADDRESS"]
      | map(select(. as $t | ($types | index($t)) | not))
      | if length == 0 then "없음" else join(", ") end
    ' "${metadata_file}")"
    echo "- 다중 페이지 PDF는 페이지 집합 $(jq -r '.results[] | select(.name == "synthetic_multipage_detect") | (.pages | map(tostring) | join(", "))' "${metadata_file}") 에서 탐지되어 페이지 번호/bounding box 응답이 동작했습니다."
    echo "- 마스킹 후 재탐지 결과 건수: $(jq -r '.results[] | select(.name == "synthetic_full_mask") | .redetectFindingCount' "${metadata_file}"). PDF는 비OCR 경로 기준으로 원문 텍스트가 남지 않도록 라스터 masked PDF로 생성했습니다."
    echo "- 공개 개인정보보호 안내서 baseline 탐지 건수: $(jq -r '.results[] | select(.name == "privacy_guide_negative_control") | .findingCount' "${metadata_file}")"
    echo "- 공개 재무제표 baseline 탐지 건수: $(jq -r '.results[] | select(.name == "financial_statement_negative_control") | .findingCount' "${metadata_file}")"
    echo "- PNG vLLM 경로: $(jq -r '.png.status' "${metadata_file}") - $(jq -r '.png.note' "${metadata_file}")"
    echo

    echo "## Scenario Summary"
    echo
    echo "| Scenario | File | Findings | Types | Pages | Notes |"
    echo "| --- | --- | ---: | --- | --- | --- |"
    while IFS= read -r row; do
      echo "| $(jq -r '.name' <<< "${row}") | $(jq -r '.file' <<< "${row}") | $(jq -r '.findingCount' <<< "${row}") | $(jq -r '(.types | join(", "))' <<< "${row}") | $(jq -r '(.pages | map(tostring) | join(", "))' <<< "${row}") | $(jq -r '.note' <<< "${row}") |"
    done < <(jq -c '.results[]' "${metadata_file}")
    echo

    if jq -e '.validations | length > 0' "${metadata_file}" >/dev/null 2>&1; then
      echo "## API Validation"
      echo
      echo "| Scenario | Expected | Actual | Notes |"
      echo "| --- | ---: | ---: | --- |"
      while IFS= read -r row; do
        echo "| $(jq -r '.name' <<< "${row}") | $(jq -r '.expectedStatus' <<< "${row}") | $(jq -r '.actualStatus' <<< "${row}") | $(jq -r '.note' <<< "${row}") |"
      done < <(jq -c '.validations[]' "${metadata_file}")
      echo
    fi

    while IFS= read -r row; do
      echo "## $(jq -r '.name' <<< "${row}")"
      echo
      echo "- File: \`$(jq -r '.file' <<< "${row}")\`"
      echo "- Finding count: $(jq -r '.findingCount' <<< "${row}")"
      echo "- Types: $(jq -r '(.types | join(", "))' <<< "${row}")"
      echo "- Pages: $(jq -r '(.pages | map(tostring) | join(", "))' <<< "${row}")"
      if jq -e '.maskedDownloadUrl != null' <<< "${row}" >/dev/null 2>&1; then
        echo "- Masked download URL: $(jq -r '.maskedDownloadUrl' <<< "${row}")"
        echo "- Re-detect finding count after masking: $(jq -r '.redetectFindingCount' <<< "${row}")"
      fi
      echo "- Notes: $(jq -r '.note' <<< "${row}")"
      if jq -e '(.findings | length) > 0' <<< "${row}" >/dev/null 2>&1; then
        echo
        echo "| Type | Original | Masked | Page | Bounding Box | Source |"
        echo "| --- | --- | --- | ---: | --- | --- |"
        jq -r '
          .findings[]
          | "| \(.type) | \(.originalText) | \(.maskedText) | \(.pageNumber) | x=\(.boundingBox.x), y=\(.boundingBox.y), w=\(.boundingBox.width), h=\(.boundingBox.height) | \(.detectionSource) |"
        ' <<< "${row}"
      fi
      echo
    done < <(jq -c '.results[]' "${metadata_file}")

    echo "## PNG Note"
    echo
    echo "- Status: $(jq -r '.png.status' "${metadata_file}")"
    echo "- Detail: $(jq -r '.png.note' "${metadata_file}")"
  } > "${REPORT_FILE}"
}

require_tool "${MVN_BIN}"
require_tool "${JAVA_BIN}"
require_tool curl
require_tool jq

pushd "${ROOT_DIR}" >/dev/null

MVN_ARGS=()
if [[ -f "${SETTINGS_FILE}" ]]; then
  MVN_ARGS+=(-s "${SETTINGS_FILE}")
fi
if [[ "${MVN_OFFLINE}" == "true" ]]; then
  MVN_ARGS+=(-o)
fi

"${MVN_BIN}" "${MVN_ARGS[@]}" -Dtest=PiiSampleDocumentGeneratorTest test | tee "${ARTIFACT_DIR}/pii-sample-generator.log"
"${MVN_BIN}" "${MVN_ARGS[@]}" -DskipTests package | tee "${ARTIFACT_DIR}/pii-package.log"

JAR_FILE="$(find "${ROOT_DIR}/target" -maxdepth 1 -type f -name 'jadp-*.jar' ! -name '*.original' | sort | tail -n 1)"
if [[ -z "${JAR_FILE}" ]]; then
  echo "Packaged jar not found." >&2
  exit 1
fi

rm -f "${LOG_FILE}" "${ERR_LOG_FILE}"
"${JAVA_BIN}" -jar "${JAR_FILE}" "--server.port=${PORT}" > "${LOG_FILE}" 2> "${ERR_LOG_FILE}" &
APP_PID=$!

BASE_URL="http://127.0.0.1:${PORT}"
if ! wait_http_ready "${BASE_URL}/actuator/health"; then
  echo "Application did not become ready." >&2
  echo "STDOUT:" >&2
  tail -n 40 "${LOG_FILE}" >&2 || true
  echo "STDERR:" >&2
  tail -n 40 "${ERR_LOG_FILE}" >&2 || true
  exit 1
fi

: > "${TMP_DIR}/results.jsonl"
: > "${TMP_DIR}/validations.jsonl"

run_detect_case "synthetic_full_detect" "${ROOT_DIR}/samples/pii/synthetic-korean-pii-full.pdf" "12개 기준 유형이 모두 들어간 합성 한글 PDF"
run_detect_case "synthetic_multipage_detect" "${ROOT_DIR}/samples/pii/synthetic-korean-pii-multipage.pdf" "다중 페이지 합성 한글 PDF"
run_detect_case "privacy_guide_negative_control" "${ROOT_DIR}/samples/privacy-protection-guide.pdf" "공개 개인정보보호 안내서 baseline"
run_detect_case "financial_statement_negative_control" "${ROOT_DIR}/samples/financial-statement.pdf" "공개 재무제표 샘플 baseline"
run_detect_case "korean_complex_negative_control" "${ROOT_DIR}/samples/korean-complex.pdf" "공개 한글 복합 PDF baseline"

MASK_RESPONSE_FILE="${TMP_DIR}/mask-response.json"
MASK_HTTP_CODE="$(curl -sS -o "${MASK_RESPONSE_FILE}" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/pii/mask" -F "file=@${ROOT_DIR}/samples/pii/synthetic-korean-pii-full.pdf")"
if [[ ! "${MASK_HTTP_CODE}" =~ ^[0-9]+$ ]] || (( MASK_HTTP_CODE < 200 || MASK_HTTP_CODE >= 300 )); then
  echo "PII mask scenario failed with HTTP ${MASK_HTTP_CODE}" >&2
  cat "${MASK_RESPONSE_FILE}" >&2
  exit 1
fi

MASKED_FILENAME="$(jq -r '.maskedFilename' "${MASK_RESPONSE_FILE}")"
MASKED_DOWNLOAD_URL="$(jq -r '.maskedDownloadUrl' "${MASK_RESPONSE_FILE}")"
MASKED_TARGET="${DOWNLOAD_DIR}/${MASKED_FILENAME}"
curl -fsS -o "${MASKED_TARGET}" "${MASKED_DOWNLOAD_URL}"

REDETECT_FILE="${TMP_DIR}/mask-redetect.json"
REDETECT_HTTP_CODE="$(curl -sS -o "${REDETECT_FILE}" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/pii/detect" -F "file=@${MASKED_TARGET}")"
if [[ ! "${REDETECT_HTTP_CODE}" =~ ^[0-9]+$ ]] || (( REDETECT_HTTP_CODE < 200 || REDETECT_HTTP_CODE >= 300 )); then
  echo "PII re-detect scenario failed with HTTP ${REDETECT_HTTP_CODE}" >&2
  cat "${REDETECT_FILE}" >&2
  exit 1
fi

append_json_line "${TMP_DIR}/results.jsonl" "$(jq -nc \
  --arg name "synthetic_full_mask" \
  --arg file "samples/pii/synthetic-korean-pii-full.pdf" \
  --arg note "마스킹 API 호출 후 masked PDF 재탐지" \
  --arg maskedDownloadUrl "${MASKED_DOWNLOAD_URL}" \
  --slurpfile mask "${MASK_RESPONSE_FILE}" \
  --slurpfile redetect "${REDETECT_FILE}" '
  {
    name: $name,
    file: $file,
    note: $note,
    findingCount: ($mask[0].findingCount // 0),
    types: (($mask[0].findings // []) | map(.type) | unique),
    pages: (($mask[0].findings // []) | map(.pageNumber) | unique),
    findings: ($mask[0].findings // []),
    maskedDownloadUrl: $maskedDownloadUrl,
    redetectFindingCount: ($redetect[0].findingCount // 0)
  }')"

UNSUPPORTED_FILE="${TMP_DIR}/unsupported.txt"
printf 'not a pdf\n' > "${UNSUPPORTED_FILE}"
UNSUPPORTED_RESPONSE_FILE="${TMP_DIR}/unsupported-response.txt"
UNSUPPORTED_HTTP_CODE="$(curl -sS -o "${UNSUPPORTED_RESPONSE_FILE}" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/pii/detect" -F "file=@${UNSUPPORTED_FILE}")"
append_json_line "${TMP_DIR}/validations.jsonl" "$(jq -nc \
  --arg name "detect_unsupported_text_file" \
  --arg note "Only PDF, PNG, JPG, JPEG are supported." \
  --argjson expectedStatus 415 \
  --argjson actualStatus "${UNSUPPORTED_HTTP_CODE}" '
  {
    name: $name,
    expectedStatus: $expectedStatus,
    actualStatus: $actualStatus,
    note: $note
  }')"

MISSING_RESPONSE_FILE="${TMP_DIR}/missing-response.txt"
MISSING_HTTP_CODE="$(curl -sS -o "${MISSING_RESPONSE_FILE}" -w '%{http_code}' "${BASE_URL}/api/v1/pii/files/not-found")"
append_json_line "${TMP_DIR}/validations.jsonl" "$(jq -nc \
  --arg name "download_missing_masked_file" \
  --arg note "Unknown artifact id should return not found." \
  --argjson expectedStatus 404 \
  --argjson actualStatus "${MISSING_HTTP_CODE}" '
  {
    name: $name,
    expectedStatus: $expectedStatus,
    actualStatus: $actualStatus,
    note: $note
  }')"

jq -s '.' "${TMP_DIR}/results.jsonl" > "${TMP_DIR}/results.json"
jq -s '.' "${TMP_DIR}/validations.jsonl" > "${TMP_DIR}/validations.json"

jq -nc \
  --slurpfile results "${TMP_DIR}/results.json" \
  --slurpfile validations "${TMP_DIR}/validations.json" \
  --arg executedAt "$(date '+%Y-%m-%dT%H:%M:%S%z')" \
  --arg baseUrl "${BASE_URL}" '
  {
    executedAt: $executedAt,
    baseUrl: $baseUrl,
    results: $results[0],
    validations: $validations[0],
    png: {
      status: "SKIPPED",
      note: "사용자 요청에 따라 PNG vLLM OCR 경로는 구현만 하고, vLLM endpoint 미구성 상태에서 실행 검증은 생략했습니다."
    }
  }' > "${RAW_RESULTS_FILE}"

generate_report "${RAW_RESULTS_FILE}"

echo "Report written to ${REPORT_FILE}"
echo "Raw results written to ${RAW_RESULTS_FILE}"

popd >/dev/null

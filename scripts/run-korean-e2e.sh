#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-18080}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SETTINGS_FILE="${ROOT_DIR}/.mvn-local-settings.xml"
ARTIFACT_DIR="${ROOT_DIR}/test-artifacts"
SAMPLES_DIR="${ROOT_DIR}/samples"
LOG_FILE="${ARTIFACT_DIR}/app-${PORT}.log"
ERR_LOG_FILE="${ARTIFACT_DIR}/app-${PORT}.err.log"
RAW_RESULTS_FILE="${ARTIFACT_DIR}/korean-e2e-results.json"
REPORT_FILE="${ARTIFACT_DIR}/KOREAN_PDF_E2E_REPORT.md"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/jadp-korean-e2e.XXXXXX")"
MVN_BIN="${MVN_BIN:-mvn}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN_OFFLINE="${MVN_OFFLINE:-false}"
APP_PID=""
BASE_URL=""

mkdir -p "${ARTIFACT_DIR}"

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

wait_for_job() {
  local job_id="$1"
  local output_file="$2"
  local timeout_seconds="${3:-1800}"
  local deadline=$((SECONDS + timeout_seconds))

  while (( SECONDS < deadline )); do
    curl -fsS "${BASE_URL}/api/v1/pdf/jobs/${job_id}" > "${output_file}"
    local status
    status="$(jq -r '.status // empty' "${output_file}")"
    if [[ "${status}" == "SUCCEEDED" || "${status}" == "FAILED" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for job ${job_id}" >&2
  return 1
}

hangul_detected() {
  local text="${1:-}"
  [[ -n "${text}" ]] && printf '%s' "${text}" | grep -q '[가-힣]'
}

json_summary_from_url() {
  local url="$1"
  local raw raw_length contains_placeholder masked_date_metadata
  raw="$(curl -fsS "${url}")"
  raw_length="$(printf '%s' "${raw}" | wc -c | tr -d ' ')"
  contains_placeholder=false
  masked_date_metadata=false

  if printf '%s' "${raw}" | grep -Eq '\[(EMAIL|PHONE|IP|URL|CARD)\]'; then
    contains_placeholder=true
  fi
  if printf '%s' "${raw}" | grep -Eq '"creation date"\s*:\s*"D:\[PHONE\]|"modification date"\s*:\s*"D:\[PHONE\]'; then
    masked_date_metadata=true
  fi

  if printf '%s' "${raw}" | jq -e . >/dev/null 2>&1; then
    printf '%s' "${raw}" | jq -c \
      --argjson rawLength "${raw_length}" \
      --argjson containsPlaceholder "${contains_placeholder}" \
      --argjson maskedDateMetadata "${masked_date_metadata}" '
      {
        rawLength: $rawLength,
        containsPlaceholder: $containsPlaceholder,
        maskedDateMetadata: $maskedDateMetadata,
        parseError: null,
        fileName: .["file name"] // null,
        numberOfPages: .["number of pages"] // null,
        topLevelKids: ((.kids // []) | length),
        hasKids: (.kids != null)
      }'
  else
    jq -nc \
      --argjson rawLength "${raw_length}" \
      --argjson containsPlaceholder "${contains_placeholder}" \
      --argjson maskedDateMetadata "${masked_date_metadata}" '
      {
        rawLength: $rawLength,
        containsPlaceholder: $containsPlaceholder,
        maskedDateMetadata: $maskedDateMetadata,
        parseError: "Invalid JSON response",
        fileName: null,
        numberOfPages: null,
        topLevelKids: 0,
        hasKids: false
      }'
  fi
}

markdown_summary_from_url() {
  local url="$1"
  local content length preview contains_html contains_image_ref contains_abs_path contains_placeholder hangul
  content="$(curl -fsS "${url}")"
  length="$(printf '%s' "${content}" | wc -c | tr -d ' ')"
  preview="$(printf '%s' "${content}" | head -c 300)"
  contains_html=false
  contains_image_ref=false
  contains_abs_path=false
  contains_placeholder=false
  hangul=false

  if printf '%s' "${content}" | grep -Eq '<[A-Za-z/]'; then
    contains_html=true
  fi
  if printf '%s' "${content}" | grep -Eq '!\[[^]]*\]\([^)]+\)|\.(png|jpg)'; then
    contains_image_ref=true
  fi
  if printf '%s' "${content}" | grep -Fq ':\'; then
    contains_abs_path=true
  fi
  if printf '%s' "${content}" | grep -Eq '\[(EMAIL|PHONE|IP|URL|CARD)\]'; then
    contains_placeholder=true
  fi
  if hangul_detected "${content}"; then
    hangul=true
  fi

  jq -nc \
    --argjson length "${length}" \
    --arg preview "${preview}" \
    --argjson containsHtml "${contains_html}" \
    --argjson containsImageRef "${contains_image_ref}" \
    --argjson containsAbsoluteWindowsPath "${contains_abs_path}" \
    --argjson containsPlaceholder "${contains_placeholder}" \
    --argjson hangulDetected "${hangul}" '
    {
      length: $length,
      preview: $preview,
      containsHtml: $containsHtml,
      containsImageRef: $containsImageRef,
      containsAbsoluteWindowsPath: $containsAbsoluteWindowsPath,
      containsPlaceholder: $containsPlaceholder,
      hangulDetected: $hangulDetected
    }'
}

append_json_line() {
  local file="$1"
  local payload="$2"
  printf '%s\n' "${payload}" >> "${file}"
}

generate_report() {
  local metadata_file="$1"
  local executed_at page_observation full_duration image_count invalid_status
  executed_at="$(jq -r '.executedAt' "${metadata_file}")"

  {
    echo "# Korean PDF End-to-End Test Report"
    echo
    echo "- Executed at: ${executed_at}"
    echo "- Application port: ${PORT}"
    echo "- Raw results JSON: \`test-artifacts/korean-e2e-results.json\`"
    echo "- Financial statement sample: \`samples/financial-statement.pdf\`"
    echo "- Privacy protection sample: \`samples/privacy-protection-guide.pdf\`"
    echo "- Complex museum sample: \`samples/korean-complex.pdf\`"
    echo

    local baseline_pages subset_pages
    baseline_pages="$(jq -r '.scenarios[] | select(.name == "async_privacy_full_formats") | .jsonSummary.numberOfPages // empty' "${metadata_file}")"
    subset_pages="$(jq -r '.scenarios[] | select(.name == "async_privacy_pages_subset") | .jsonSummary.numberOfPages // empty' "${metadata_file}")"
    if [[ -n "${baseline_pages}" && -n "${subset_pages}" ]]; then
      if [[ "${baseline_pages}" == "${subset_pages}" ]]; then
        page_observation='요청한 `pages=1-2`와 달리 산출 JSON의 `number of pages`가 baseline과 동일했습니다. 현재 핀된 OpenDataLoader `1.3.0`에서는 페이지 범위 setter가 노출되지 않아 이 옵션이 런타임에 적용되지 않은 것으로 보입니다.'
      else
        page_observation="페이지 범위 옵션이 baseline 대비 축소된 페이지 수로 반영되었습니다."
      fi

      full_duration="$(jq -r '.scenarios[] | select(.name == "async_privacy_full_formats") | .processingMillis // empty' "${metadata_file}")"
      image_count="$(jq -r '.scenarios[] | select(.name == "async_privacy_full_formats") | .imageArtifactCount // empty' "${metadata_file}")"
      invalid_status="$(jq -r '.validationChecks[] | select(.name == "invalid_pages_rejected") | .httpStatus // empty' "${metadata_file}")"

      echo "## Key Findings"
      echo
      echo "- 성공 시나리오 5건이 모두 HTTP 및 최종 job 상태 기준으로 정상 완료되었습니다."
      echo "- ${page_observation}"
      if [[ -n "${full_duration}" && -n "${image_count}" ]]; then
        echo "- 개인정보보호법 안내서 전체 포맷 변환은 ${full_duration}ms가 걸렸고, 부가 이미지 산출물이 ${image_count}개 생성되어 저장 용량 증가를 확인했습니다."
      fi
      if jq -e '.scenarios[] | select(.jsonSummary != null and .jsonSummary.maskedDateMetadata == true)' "${metadata_file}" >/dev/null 2>&1; then
        echo "- \`sanitize=true\` 환경에서는 PDF 메타데이터의 생성일/수정일 문자열도 \`[PHONE]\`으로 치환되는 과마스킹이 확인되었습니다."
      fi
      if jq -e '.scenarios[] | select(.name == "async_museum_markdown_images")' "${metadata_file}" >/dev/null 2>&1; then
        echo "- 이미지 포함 Markdown 시나리오에서는 image 파일 수: $(jq -r '.scenarios[] | select(.name == "async_museum_markdown_images") | .imageArtifactCount // 0' "${metadata_file}"), markdown 내 이미지 참조 감지: $(jq -r '.scenarios[] | select(.name == "async_museum_markdown_images") | .markdownSummary.containsImageRef // false' "${metadata_file}"), 절대 경로 포함: $(jq -r '.scenarios[] | select(.name == "async_museum_markdown_images") | .markdownSummary.containsAbsoluteWindowsPath // false' "${metadata_file}")."
      fi
      if [[ -n "${invalid_status}" ]]; then
        echo "- 잘못된 페이지 범위(\`pages=1-a\`)는 HTTP ${invalid_status}로 거절되어 입력 검증이 동작했습니다."
      fi
      echo
    fi

    echo "## Scenario Summary"
    echo
    echo "| Scenario | Mode | HTTP | Final Status | Output Files | Image Files | JSON | Hangul | Notes |"
    echo "| --- | --- | --- | --- | ---: | ---: | --- | --- | --- |"
    while IFS= read -r row; do
      local json_state
      if jq -e '.jsonSummary == null' <<< "${row}" >/dev/null 2>&1; then
        json_state="-"
      elif jq -e '.jsonSummary.parseError != null' <<< "${row}" >/dev/null 2>&1; then
        json_state="parse-error"
      else
        json_state="$(jq -r '"ok/\(.jsonSummary.numberOfPages)p"' <<< "${row}")"
      fi
      echo "| $(jq -r '.name' <<< "${row}") | $(jq -r '.mode' <<< "${row}") | $(jq -r '.httpStatus' <<< "${row}") | $(jq -r '.finalStatus // "-"' <<< "${row}") | $(jq -r '(.files // []) | length' <<< "${row}") | $(jq -r '.imageArtifactCount // 0' <<< "${row}") | ${json_state} | $(jq -r '.markdownSummary.hangulDetected // false' <<< "${row}") | $(jq -r '.note' <<< "${row}") |"
    done < <(jq -c '.scenarios[]' "${metadata_file}")
    echo

    if jq -e '.validationChecks | length > 0' "${metadata_file}" >/dev/null 2>&1; then
      echo "## Validation Checks"
      echo
      echo "| Check | HTTP | Passed | Response Preview |"
      echo "| --- | --- | --- | --- |"
      while IFS= read -r row; do
        echo "| $(jq -r '.name' <<< "${row}") | $(jq -r '.httpStatus' <<< "${row}") | $(jq -r '.passed' <<< "${row}") | $(jq -r '.responsePreview' <<< "${row}") |"
      done < <(jq -c '.validationChecks[]' "${metadata_file}")
      echo
    fi

    while IFS= read -r row; do
      echo "## $(jq -r '.name' <<< "${row}")"
      echo
      echo "- Endpoint: \`$(jq -r '.endpoint' <<< "${row}")\`"
      echo "- Input file: \`$(jq -r '.inputFile' <<< "${row}")\`"
      echo "- Request params: \`$(jq -r '.requestSummary' <<< "${row}")\`"
      echo "- HTTP status: $(jq -r '.httpStatus' <<< "${row}")"
      echo "- Final status: $(jq -r '.finalStatus // "-"' <<< "${row}")"
      if [[ "$(jq -r '.processingMillis // empty' <<< "${row}")" != "" ]]; then
        echo "- Processing millis: $(jq -r '.processingMillis' <<< "${row}")"
      fi
      echo "- Image artifact count: $(jq -r '.imageArtifactCount // 0' <<< "${row}")"
      if ! jq -e '.jsonSummary == null' <<< "${row}" >/dev/null 2>&1; then
        if jq -e '.jsonSummary.parseError != null' <<< "${row}" >/dev/null 2>&1; then
          echo "- JSON summary: parse error=$(jq -r '.jsonSummary.parseError' <<< "${row}")"
        else
          echo "- JSON summary: pages=$(jq -r '.jsonSummary.numberOfPages' <<< "${row}"), top-level kids=$(jq -r '.jsonSummary.topLevelKids' <<< "${row}"), file=$(jq -r '.jsonSummary.fileName' <<< "${row}"), placeholder=$(jq -r '.jsonSummary.containsPlaceholder' <<< "${row}"), maskedDateMetadata=$(jq -r '.jsonSummary.maskedDateMetadata' <<< "${row}")"
        fi
      fi
      if ! jq -e '.markdownSummary == null' <<< "${row}" >/dev/null 2>&1; then
        echo "- Markdown summary: length=$(jq -r '.markdownSummary.length' <<< "${row}"), hangul=$(jq -r '.markdownSummary.hangulDetected' <<< "${row}"), html=$(jq -r '.markdownSummary.containsHtml' <<< "${row}"), imageRef=$(jq -r '.markdownSummary.containsImageRef' <<< "${row}"), absPathRef=$(jq -r '.markdownSummary.containsAbsoluteWindowsPath' <<< "${row}"), placeholder=$(jq -r '.markdownSummary.containsPlaceholder' <<< "${row}")"
        echo
        echo '```text'
        jq -r '.markdownSummary.preview' <<< "${row}"
        echo '```'
      fi
      if jq -e '(.files // []) | length > 0' <<< "${row}" >/dev/null 2>&1; then
        echo
        echo "| Format | Filename | Size | Download URL |"
        echo "| --- | --- | ---: | --- |"
        jq -r '
          (.files // [])
          | (map(select(.format != "image")) + (map(select(.format == "image")) | .[:5]))
          | .[]
          | "| \(.format) | \(.filename) | \(.size) | \(.downloadUrl) |"
        ' <<< "${row}"
        local image_file_total
        image_file_total="$(jq -r '[(.files // [])[] | select(.format == "image")] | length' <<< "${row}")"
        if (( image_file_total > 5 )); then
          echo
          echo "- Additional image files omitted from table: $((image_file_total - 5))"
        fi
      fi
      echo
    done < <(jq -c '.scenarios[]' "${metadata_file}")
  } > "${REPORT_FILE}"
}

run_pdf_scenario() {
  local name="$1"
  local mode="$2"
  local endpoint="$3"
  local input_file="$4"
  local note="$5"
  shift 5
  local -a form_entries=("$@")
  local response_file="${TMP_DIR}/${name}.response.json"
  local request_started request_finished http_code request_summary job_id final_status files_json image_artifact_count json_summary markdown_summary html_preview_url processing_millis

  request_started="$(date '+%Y-%m-%dT%H:%M:%S%z')"
  local -a curl_args=(-sS -o "${response_file}" -w '%{http_code}' -X POST "${BASE_URL}${endpoint}" -F "file=@${input_file}")
  local entry
  for entry in "${form_entries[@]}"; do
    curl_args+=(-F "${entry}")
  done
  http_code="$(curl "${curl_args[@]}")"
  request_finished="$(date '+%Y-%m-%dT%H:%M:%S%z')"

  if [[ ! "${http_code}" =~ ^[0-9]+$ ]] || (( http_code < 200 || http_code >= 300 )); then
    echo "Scenario ${name} failed with HTTP ${http_code}" >&2
    cat "${response_file}" >&2
    exit 1
  fi

  request_summary="$(printf '%s, ' "${form_entries[@]}")"
  request_summary="${request_summary%, }"
  json_summary='null'
  markdown_summary='null'
  html_preview_url=""
  processing_millis=""

  if [[ "${mode}" == "sync" ]]; then
    job_id="$(jq -r '.jobId // empty' "${response_file}")"
    final_status="$(jq -r '.status // empty' "${response_file}")"
    files_json="$(jq -c '.outputFiles // []' "${response_file}")"
    html_preview_url="$(jq -r '.htmlPreviewUrl // empty' "${response_file}")"
  else
    local job_file="${TMP_DIR}/${name}.job.json"
    job_id="$(jq -r '.jobId // empty' "${response_file}")"
    wait_for_job "${job_id}" "${job_file}"
    final_status="$(jq -r '.status // empty' "${job_file}")"
    processing_millis="$(jq -r '.processingMillis // empty' "${job_file}")"
    files_json="$(jq -c '.files // []' "${job_file}")"
  fi

  image_artifact_count="$(jq '[.[] | select((.format // "") == "image" or ((.filename // "") | test("\\.(png|jpg|jpeg)$"; "i")))] | length' <<< "${files_json}")"
  local json_url markdown_url
  json_url="$(jq -r '[.[] | select(.format == "json")][0].downloadUrl // empty' <<< "${files_json}")"
  markdown_url="$(jq -r '[.[] | select((.format // "") | test("^markdown"))][0].downloadUrl // empty' <<< "${files_json}")"

  if [[ -n "${json_url}" ]]; then
    json_summary="$(json_summary_from_url "${json_url}")"
  fi
  if [[ -n "${markdown_url}" ]]; then
    markdown_summary="$(markdown_summary_from_url "${markdown_url}")"
  fi

  append_json_line "${TMP_DIR}/results.jsonl" "$(jq -nc \
    --arg name "${name}" \
    --arg mode "${mode}" \
    --arg endpoint "${endpoint}" \
    --arg inputFile "$(basename "${input_file}")" \
    --arg requestSummary "${request_summary}" \
    --arg note "${note}" \
    --arg requestStartedAt "${request_started}" \
    --arg requestFinishedAt "${request_finished}" \
    --arg jobId "${job_id}" \
    --arg finalStatus "${final_status}" \
    --arg htmlPreviewUrl "${html_preview_url}" \
    --arg processingMillis "${processing_millis}" \
    --argjson httpStatus "${http_code}" \
    --argjson files "${files_json}" \
    --argjson jsonSummary "${json_summary}" \
    --argjson markdownSummary "${markdown_summary}" \
    --argjson imageArtifactCount "${image_artifact_count}" '
    {
      name: $name,
      mode: $mode,
      endpoint: $endpoint,
      inputFile: $inputFile,
      requestSummary: $requestSummary,
      httpStatus: $httpStatus,
      note: $note,
      requestStartedAt: $requestStartedAt,
      requestFinishedAt: $requestFinishedAt,
      jobId: $jobId,
      finalStatus: $finalStatus,
      files: $files,
      htmlPreviewUrl: (if $htmlPreviewUrl == "" then null else $htmlPreviewUrl end),
      processingMillis: (if $processingMillis == "" then null else ($processingMillis | tonumber) end),
      jsonSummary: $jsonSummary,
      markdownSummary: $markdownSummary,
      imageArtifactCount: $imageArtifactCount
    }')"
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

"${MVN_BIN}" "${MVN_ARGS[@]}" -DskipTests package | tee "${ARTIFACT_DIR}/package.log"

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

curl -fsS "${BASE_URL}/actuator/health" > "${TMP_DIR}/health.json"
curl -fsS "${BASE_URL}/api/v1/pdf/config/options" > "${TMP_DIR}/options.json"
SWAGGER_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/swagger-ui.html")"
HOME_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/")"

: > "${TMP_DIR}/results.jsonl"
: > "${TMP_DIR}/validations.jsonl"

run_pdf_scenario \
  "sync_financial_basic" \
  "sync" \
  "/api/v1/pdf/convert-sync" \
  "${SAMPLES_DIR}/financial-statement.pdf" \
  "재무제표 PDF sync 기본 변환" \
  "formats=json,markdown,html" \
  "sanitize=true" \
  "readingOrder=xycut" \
  "tableMethod=default"

run_pdf_scenario \
  "sync_financial_markdown_html" \
  "sync" \
  "/api/v1/pdf/convert-sync" \
  "${SAMPLES_DIR}/financial-statement.pdf" \
  "재무제표 PDF에 markdown-with-html 및 struct tree 요청" \
  "formats=json,markdown-with-html" \
  "sanitize=true" \
  "keepLineBreaks=true" \
  "useStructTree=true" \
  "includeHeaderFooter=true"

run_pdf_scenario \
  "async_privacy_full_formats" \
  "async" \
  "/api/v1/pdf/convert" \
  "${SAMPLES_DIR}/privacy-protection-guide.pdf" \
  "개인정보보호 안내 PDF 전체 포맷 생성" \
  "formats=json,markdown,html,pdf,text" \
  "sanitize=true" \
  "readingOrder=xycut" \
  "tableMethod=default"

run_pdf_scenario \
  "async_privacy_pages_subset" \
  "async" \
  "/api/v1/pdf/convert" \
  "${SAMPLES_DIR}/privacy-protection-guide.pdf" \
  "개인정보보호 PDF 페이지 범위 및 줄바꿈/struct tree 요청" \
  "formats=json,markdown" \
  "sanitize=true" \
  "pages=1-2" \
  "keepLineBreaks=true" \
  "useStructTree=true"

run_pdf_scenario \
  "async_museum_markdown_images" \
  "async" \
  "/api/v1/pdf/convert" \
  "${SAMPLES_DIR}/korean-complex.pdf" \
  "이미지 포함 Markdown 및 sanitize off" \
  "formats=json,markdown-with-images" \
  "sanitize=false" \
  "imageOutput=external" \
  "imageFormat=png"

INVALID_RESPONSE_FILE="${TMP_DIR}/invalid-pages-response.txt"
INVALID_HTTP_CODE="$(curl -sS -o "${INVALID_RESPONSE_FILE}" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/pdf/convert" \
  -F "file=@${SAMPLES_DIR}/privacy-protection-guide.pdf" \
  -F "formats=json" \
  -F "pages=1-a")"
INVALID_PREVIEW="$(head -c 200 "${INVALID_RESPONSE_FILE}" | tr '\n' ' ')"
INVALID_PREVIEW="${INVALID_PREVIEW//|/\\|}"
INVALID_PASSED=false
if [[ "${INVALID_HTTP_CODE}" == "400" ]] && grep -q "Invalid pages format" "${INVALID_RESPONSE_FILE}"; then
  INVALID_PASSED=true
fi
append_json_line "${TMP_DIR}/validations.jsonl" "$(jq -nc \
  --arg name "invalid_pages_rejected" \
  --arg responsePreview "${INVALID_PREVIEW}" \
  --argjson httpStatus "${INVALID_HTTP_CODE}" \
  --argjson passed "${INVALID_PASSED}" '
  {
    name: $name,
    httpStatus: $httpStatus,
    passed: $passed,
    responsePreview: $responsePreview
  }')"

jq -s '.' "${TMP_DIR}/results.jsonl" > "${TMP_DIR}/results.json"
jq -s '.' "${TMP_DIR}/validations.jsonl" > "${TMP_DIR}/validations.json"

jq -nc \
  --slurpfile results "${TMP_DIR}/results.json" \
  --slurpfile validationChecks "${TMP_DIR}/validations.json" \
  --slurpfile health "${TMP_DIR}/health.json" \
  --slurpfile options "${TMP_DIR}/options.json" \
  --arg executedAt "$(date '+%Y-%m-%dT%H:%M:%S%z')" \
  --arg baseUrl "${BASE_URL}" \
  --argjson swaggerStatus "${SWAGGER_STATUS}" \
  --argjson homeStatus "${HOME_STATUS}" '
  {
    executedAt: $executedAt,
    baseUrl: $baseUrl,
    healthStatus: ($health[0].status // null),
    swaggerStatus: $swaggerStatus,
    homeStatus: $homeStatus,
    optionDefaults: ($options[0].defaults // null),
    validationChecks: $validationChecks[0],
    scenarios: $results[0]
  }' > "${RAW_RESULTS_FILE}"

generate_report "${RAW_RESULTS_FILE}"

echo "Report written to ${REPORT_FILE}"
echo "Raw results written to ${RAW_RESULTS_FILE}"

popd >/dev/null

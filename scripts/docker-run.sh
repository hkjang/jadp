#!/usr/bin/env bash
set -euo pipefail

APP_IMAGE="${APP_IMAGE:-jadp:latest}"
HYBRID_VARIANT="${HYBRID_VARIANT:-cpu}"
HYBRID_IMAGE="${HYBRID_IMAGE:-}"
APP_CONTAINER="${APP_CONTAINER:-jadp-app}"
HYBRID_CONTAINER="${HYBRID_CONTAINER:-jadp-hybrid}"
NETWORK_NAME="${NETWORK_NAME:-jadp-net}"
APP_PORT="${APP_PORT:-8080}"
HYBRID_PORT="${HYBRID_PORT:-5002}"
STORAGE_DIR="${STORAGE_DIR:-var/docker-app-data}"
HYBRID_CACHE_DIR="${HYBRID_CACHE_DIR:-var/docker-hybrid-cache}"
USE_HYBRID="${USE_HYBRID:-true}"
AUTO_APPLY_TO_REQUESTS="${AUTO_APPLY_TO_REQUESTS:-true}"
AUTO_APPLY_TO_PII="${AUTO_APPLY_TO_PII:-true}"
PREFER_FULL_MODE="${PREFER_FULL_MODE:-false}"
ENABLE_PICTURE_DESCRIPTION="${ENABLE_PICTURE_DESCRIPTION:-false}"
FORCE_OCR="${FORCE_OCR:-false}"
OCR_LANG="${OCR_LANG:-ko,en}"
HYBRID_TIMEOUT_MILLIS="${HYBRID_TIMEOUT_MILLIS:-120000}"
HYBRID_FALLBACK="${HYBRID_FALLBACK:-true}"
HYBRID_LOG_LEVEL="${HYBRID_LOG_LEVEL:-info}"
JAVA_OPTS="${JAVA_OPTS:-}"
APP_STARTUP_TIMEOUT_SEC="${APP_STARTUP_TIMEOUT_SEC:-60}"
HYBRID_STARTUP_TIMEOUT_SEC="${HYBRID_STARTUP_TIMEOUT_SEC:-120}"
RUN_SMOKE_TEST="${RUN_SMOKE_TEST:-false}"

if [[ -z "${HYBRID_IMAGE}" ]]; then
  if [[ "${HYBRID_VARIANT}" == "gpu" ]]; then
    HYBRID_IMAGE="jadp-hybrid-gpu:latest"
  else
    HYBRID_IMAGE="jadp-hybrid-cpu:latest"
  fi
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_STORAGE_PATH="${ROOT_DIR}/${STORAGE_DIR}"
HYBRID_CACHE_PATH="${ROOT_DIR}/${HYBRID_CACHE_DIR}"

wait_for_http() {
  local url="$1"
  local timeout_sec="$2"
  local label="$3"
  local container_name="$4"
  local started_at
  started_at="$(date +%s)"

  while true; do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      echo "${label} ready: ${url}"
      return 0
    fi

    if ! docker ps --format '{{.Names}}' | grep -Fx "${container_name}" >/dev/null 2>&1; then
      echo "${label} container '${container_name}' is not running." >&2
      docker ps -a --filter "name=${container_name}"
      docker logs "${container_name}" --tail 200 || true
      return 1
    fi

    if (( "$(date +%s)" - started_at >= timeout_sec )); then
      echo "${label} did not become ready within ${timeout_sec}s: ${url}" >&2
      docker ps -a --filter "name=${container_name}"
      docker logs "${container_name}" --tail 200 || true
      return 1
    fi

    sleep 2
  done
}

mkdir -p "${APP_STORAGE_PATH}" "${HYBRID_CACHE_PATH}"

# Ensure the app storage directory is writable by the 'spring' user (UID 999) inside the container.
# UID 999 is fixed in the Dockerfile – no need to spin up a container to query it.
SPRING_UID=999
CURRENT_UID=$(stat -c '%u' "${APP_STORAGE_PATH}" 2>/dev/null \
  || stat -f '%u' "${APP_STORAGE_PATH}" 2>/dev/null \
  || echo "0")
if [ "${CURRENT_UID}" != "${SPRING_UID}" ]; then
  echo "Fixing ownership of ${APP_STORAGE_PATH} for spring user (UID ${SPRING_UID})..."
  sudo chown -R "${SPRING_UID}:${SPRING_UID}" "${APP_STORAGE_PATH}" 2>/dev/null \
    || echo "WARNING: Could not chown ${APP_STORAGE_PATH}. Run: sudo chown -R ${SPRING_UID}:${SPRING_UID} ${APP_STORAGE_PATH}"
fi

if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
  docker network create "${NETWORK_NAME}" >/dev/null
fi

docker rm -f "${APP_CONTAINER}" >/dev/null 2>&1 || true
if [[ "${USE_HYBRID}" == "true" ]]; then
  docker rm -f "${HYBRID_CONTAINER}" >/dev/null 2>&1 || true
fi

if [[ "${USE_HYBRID}" == "true" ]]; then
  HYBRID_ARGS=(
    run -d
    --name "${HYBRID_CONTAINER}"
    --network "${NETWORK_NAME}"
    -p "${HYBRID_PORT}:5002"
    -v "${HYBRID_CACHE_PATH}:/root/.cache"
    -e "HYBRID_HOST=0.0.0.0"
    -e "HYBRID_PORT=5002"
    -e "HYBRID_LOG_LEVEL=${HYBRID_LOG_LEVEL}"
  )

  if [[ "${HYBRID_VARIANT}" == "gpu" ]]; then
    HYBRID_ARGS+=(--gpus all)
  fi

  if [[ "${FORCE_OCR}" == "true" ]]; then
    HYBRID_ARGS+=(-e "HYBRID_FORCE_OCR=true" -e "HYBRID_OCR_LANG=${OCR_LANG}")
  fi

  if [[ "${ENABLE_PICTURE_DESCRIPTION}" == "true" ]]; then
    HYBRID_ARGS+=(-e "HYBRID_ENRICH_PICTURE_DESCRIPTION=true")
    PREFER_FULL_MODE="true"
  fi

  HYBRID_ARGS+=("${HYBRID_IMAGE}")
  docker "${HYBRID_ARGS[@]}" >/dev/null
fi

APP_ARGS=(
  run -d
  --name "${APP_CONTAINER}"
  --network "${NETWORK_NAME}"
  -p "${APP_PORT}:8080"
  -v "${APP_STORAGE_PATH}:/var/app-data"
  -e "APP_STORAGE_BASE_DIR=/var/app-data"
  -e "JAVA_OPTS=${JAVA_OPTS}"
)

if [[ "${USE_HYBRID}" == "true" ]]; then
  HYBRID_MODE="auto"
  if [[ "${PREFER_FULL_MODE}" == "true" ]]; then
    HYBRID_MODE="full"
  fi

  APP_ARGS+=(
    -e "APP_OPENDATALOADER_HYBRID_ENABLED=true"
    -e "APP_OPENDATALOADER_HYBRID_BACKEND=docling-fast"
    -e "APP_OPENDATALOADER_HYBRID_MODE=${HYBRID_MODE}"
    -e "APP_OPENDATALOADER_HYBRID_URL=http://${HYBRID_CONTAINER}:5002"
    -e "APP_OPENDATALOADER_HYBRID_TIMEOUT_MILLIS=${HYBRID_TIMEOUT_MILLIS}"
    -e "APP_OPENDATALOADER_HYBRID_FALLBACK=${HYBRID_FALLBACK}"
    -e "APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_REQUESTS=${AUTO_APPLY_TO_REQUESTS}"
    -e "APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_PII=${AUTO_APPLY_TO_PII}"
    -e "APP_OPENDATALOADER_HYBRID_PREFER_FULL_MODE=${PREFER_FULL_MODE}"
  )
else
  APP_ARGS+=(-e "APP_OPENDATALOADER_HYBRID_ENABLED=false")
fi

APP_ARGS+=("${APP_IMAGE}")
docker "${APP_ARGS[@]}" >/dev/null

if [[ "${USE_HYBRID}" == "true" ]]; then
  wait_for_http "http://localhost:${HYBRID_PORT}/docs" "${HYBRID_STARTUP_TIMEOUT_SEC}" "Hybrid API" "${HYBRID_CONTAINER}"
fi

wait_for_http "http://localhost:${APP_PORT}/actuator/health" "${APP_STARTUP_TIMEOUT_SEC}" "JADP app" "${APP_CONTAINER}"

echo "JADP app  : http://localhost:${APP_PORT}"
echo "Swagger   : http://localhost:${APP_PORT}/swagger-ui.html"
echo "Health    : http://localhost:${APP_PORT}/actuator/health"
echo "App logs  : docker logs ${APP_CONTAINER} --tail 200"
echo "App ports : docker ps --filter name=${APP_CONTAINER}"
if [[ "${USE_HYBRID}" == "true" ]]; then
  echo "Hybrid API: http://localhost:${HYBRID_PORT}"
  echo "Hybrid tag: ${HYBRID_IMAGE} (${HYBRID_VARIANT})"
  echo "Hybrid logs: docker logs ${HYBRID_CONTAINER} --tail 200"
fi

if [[ "${RUN_SMOKE_TEST}" == "true" ]]; then
  SMOKE_SCRIPT="${ROOT_DIR}/scripts/docker-smoke-test.sh"
  if [[ -x "${SMOKE_SCRIPT}" ]]; then
    echo ""
    echo "Running smoke tests..."
    APP_PORT="${APP_PORT}" HYBRID_PORT="${HYBRID_PORT}" bash "${SMOKE_SCRIPT}" \
      "http://localhost:${APP_PORT}" "http://localhost:${HYBRID_PORT}"
  else
    echo "WARNING: Smoke test script not found or not executable: ${SMOKE_SCRIPT}"
  fi
fi

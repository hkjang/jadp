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

mkdir -p "${APP_STORAGE_PATH}" "${HYBRID_CACHE_PATH}"

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

echo "JADP app  : http://localhost:${APP_PORT}"
echo "Swagger   : http://localhost:${APP_PORT}/swagger-ui.html"
echo "Health    : http://localhost:${APP_PORT}/actuator/health"
if [[ "${USE_HYBRID}" == "true" ]]; then
  echo "Hybrid API: http://localhost:${HYBRID_PORT}"
  echo "Hybrid tag: ${HYBRID_IMAGE} (${HYBRID_VARIANT})"
fi

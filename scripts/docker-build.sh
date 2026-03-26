#!/usr/bin/env bash
set -euo pipefail

APP_IMAGE="${APP_IMAGE:-jadp:latest}"
HYBRID_CPU_IMAGE="${HYBRID_CPU_IMAGE:-jadp-hybrid-cpu:latest}"
HYBRID_GPU_IMAGE="${HYBRID_GPU_IMAGE:-jadp-hybrid-gpu:latest}"
HYBRID_TARGET="${HYBRID_TARGET:-cpu}"
RUN_TESTS="${RUN_TESTS:-false}"
SKIP_APP="${SKIP_APP:-false}"
SKIP_HYBRID="${SKIP_HYBRID:-false}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if [[ "${SKIP_APP}" != "true" ]]; then
  MVN_CMD="${MAVEN_CMD:-mvn}"
  MVN_ARGS=(-s .mvn-local-settings.xml)
  if [[ "${RUN_TESTS}" != "true" ]]; then
    MVN_ARGS+=(-DskipTests)
  fi
  MVN_ARGS+=(package)

  "${MVN_CMD}" "${MVN_ARGS[@]}"

  JAR_FILE="$(find target -maxdepth 1 -type f -name '*.jar' ! -name '*original*' | head -n 1)"
  if [[ -z "${JAR_FILE}" ]]; then
    echo "Executable jar not found under target/" >&2
    exit 1
  fi

  docker build --build-arg "JAR_FILE=${JAR_FILE}" -t "${APP_IMAGE}" -f Dockerfile .
fi

if [[ "${SKIP_HYBRID}" != "true" ]]; then
  if [[ "${HYBRID_TARGET}" == "cpu" || "${HYBRID_TARGET}" == "all" ]]; then
    docker build -t "${HYBRID_CPU_IMAGE}" -f docker/hybrid/Dockerfile.cpu .
  fi
  if [[ "${HYBRID_TARGET}" == "gpu" || "${HYBRID_TARGET}" == "all" ]]; then
    docker build -t "${HYBRID_GPU_IMAGE}" -f docker/hybrid/Dockerfile.gpu .
  fi
fi

echo "App image: ${APP_IMAGE}"
echo "Hybrid CPU image: ${HYBRID_CPU_IMAGE}"
echo "Hybrid GPU image: ${HYBRID_GPU_IMAGE}"

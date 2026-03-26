#!/bin/sh
set -eu

set -- \
  --host "${HYBRID_HOST:-0.0.0.0}" \
  --port "${HYBRID_PORT:-5002}" \
  --log-level "${HYBRID_LOG_LEVEL:-info}"

if [ -n "${HYBRID_OCR_LANG:-}" ]; then
  set -- "$@" --ocr-lang "${HYBRID_OCR_LANG}"
fi

if [ "${HYBRID_FORCE_OCR:-false}" = "true" ]; then
  set -- "$@" --force-ocr
fi

if [ "${HYBRID_ENRICH_FORMULA:-false}" = "true" ]; then
  set -- "$@" --enrich-formula
fi

if [ "${HYBRID_ENRICH_PICTURE_DESCRIPTION:-false}" = "true" ]; then
  set -- "$@" --enrich-picture-description
fi

if [ -n "${HYBRID_PICTURE_DESCRIPTION_PROMPT:-}" ]; then
  set -- "$@" --picture-description-prompt "${HYBRID_PICTURE_DESCRIPTION_PROMPT}"
fi

exec opendataloader-pdf-hybrid "$@"

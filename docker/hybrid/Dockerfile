FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_EXTRA_INDEX_URL=https://download.pytorch.org/whl/cpu \
    HYBRID_HOST=0.0.0.0 \
    HYBRID_PORT=5002 \
    HYBRID_LOG_LEVEL=info

WORKDIR /opt/hybrid

RUN apt-get update \
    && apt-get install -y --no-install-recommends libglib2.0-0 libgl1 libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch==2.11.0 torchvision==0.26.0 \
    && pip install --no-cache-dir "opendataloader-pdf[hybrid]"

COPY docker/hybrid/entrypoint.sh /usr/local/bin/jadp-hybrid-entrypoint

RUN chmod +x /usr/local/bin/jadp-hybrid-entrypoint

EXPOSE 5002

ENTRYPOINT ["jadp-hybrid-entrypoint"]

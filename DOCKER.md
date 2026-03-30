# Docker 운영 가이드

JADP는 두 개의 컨테이너를 조합해 하이브리드 PDF 처리를 사용할 수 있습니다.

- `jadp`: Spring Boot API, Swagger, 테스트 콘솔, PII 탐지/마스킹 서비스
- `jadp-hybrid-cpu`: CPU 전용 하이브리드 서버
- `jadp-hybrid-gpu`: GPU 전용 하이브리드 서버

앱 이미지는 `fonts-noto-cjk` 를 포함하므로, masked PDF 오버레이에서도 한글이 깨지지 않도록 기본 설정돼 있습니다.

공식 하이브리드 문서는 [Hybrid Mode](https://opendataloader.org/docs/hybrid-mode)입니다. 이 문서 기준으로 현재 스택은 다음 원칙을 따릅니다.

- 기본 PDF 파이프라인은 계속 OpenDataLoader PDF Java 경로를 사용합니다.
- 하이브리드가 켜지면 복잡한 페이지를 `docling-fast` 백엔드로 라우팅합니다.
- 차트/이미지 설명을 출력에 포함하려면 서버 쪽 `--enrich-picture-description`뿐 아니라 클라이언트 요청 쪽 `hybrid-mode=full`도 함께 필요합니다.
- 스캔 PDF 전용 OCR은 옵션이며 기본값이 아닙니다. 디지털 PDF는 OCR 없이 처리하는 현재 정책을 유지합니다.

## 준비

- Docker Desktop 또는 Docker Engine
- JDK 17
- Maven 3.9+

PowerShell 기준 빌드:

```powershell
pwsh .\scripts\docker-build.ps1
```

Bash 기준 빌드:

```bash
chmod +x ./scripts/docker-build.sh ./scripts/docker-run.sh
bash ./scripts/docker-build.sh
```

위 스크립트는 다음을 수행합니다.

1. 호스트에서 Spring Boot 실행용 JAR 생성
2. `Dockerfile`로 `jadp:latest` 이미지 생성
3. CPU면 `docker/hybrid/Dockerfile.cpu`, GPU면 `docker/hybrid/Dockerfile.gpu`로 별도 이미지 생성

CPU 이미지는 CPU-only PyTorch wheel 을 우선 사용하도록 구성했습니다. 일반 서버에서 이미지 크기와 초기 다운로드 부담을 낮추는 목적입니다. GPU 이미지는 CUDA 사용자 공간 라이브러리를 포함하므로 더 크지만, GPU 서버에서 그대로 분리 운영할 수 있습니다.

## CPU / GPU 분리 전략

- CPU 서버: `jadp-hybrid-cpu:latest`
- GPU 서버: `jadp-hybrid-gpu:latest`

PowerShell:

```powershell
pwsh .\scripts\docker-build.ps1 -SkipApp -HybridTarget cpu
pwsh .\scripts\docker-build.ps1 -SkipApp -HybridTarget gpu
```

Bash:

```bash
HYBRID_TARGET=cpu bash ./scripts/docker-build.sh
HYBRID_TARGET=gpu bash ./scripts/docker-build.sh
```

## 실행

기본 CPU 하이브리드 실행:

```powershell
pwsh .\scripts\docker-run.ps1
```

```bash
bash ./scripts/docker-run.sh
```

기본값:

- 앱 포트 `8080`
- 하이브리드 포트 `5002`
- 하이브리드 변형 `cpu`
- `APP_OPENDATALOADER_HYBRID_ENABLED=true`
- `APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_REQUESTS=true`
- `APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_PII=true`
- `hybrid=docling-fast`
- `hybrid-mode=auto`

GPU 서버에서 실행:

```powershell
pwsh .\scripts\docker-run.ps1 -HybridVariant gpu
```

```bash
HYBRID_VARIANT=gpu bash ./scripts/docker-run.sh
```

GPU 변형은 하이브리드 컨테이너에 `--gpus all` 을 추가합니다.

실행 후 확인 경로:

- `http://localhost:8080`
- `http://localhost:8080/swagger-ui.html`
- `http://localhost:8080/actuator/health`

호스트 포트를 바꿨다면 그 포트로 확인합니다. 예를 들어 `8050:8080` 으로 띄운 경우:

```bash
curl http://localhost:8050/actuator/health
docker ps --filter name=jadp-app
docker logs jadp-app --tail 200
```

앱 로그의 `Tomcat started on port 8080` 은 컨테이너 내부 포트이고, 외부 접속 포트는 `docker ps` 의 `0.0.0.0:8050->8080/tcp` 매핑으로 확인합니다.

최신 실행 스크립트는 단순히 `docker run` 만 하고 끝나지 않고, 앱 `/actuator/health` 와 하이브리드 `/docs` 응답이 실제로 살아날 때까지 기다립니다. 준비되지 않으면 바로 `docker logs` 를 출력하고 실패로 종료합니다.

## 이미지와 차트 설명 강화

PDF 안에 그림, 차트, 도식이 많고 설명 텍스트까지 결과에 포함하고 싶다면 아래처럼 실행합니다.

```powershell
pwsh .\scripts\docker-run.ps1 -EnablePictureDescription $true -PreferFullMode $true
```

```bash
ENABLE_PICTURE_DESCRIPTION=true PREFER_FULL_MODE=true bash ./scripts/docker-run.sh
```

이 설정은 다음 두 가지를 함께 켭니다.

- 하이브리드 서버: `--enrich-picture-description`
- JADP 앱 기본 요청 모드: `hybrid-mode=full`

공식 문서상 picture description은 서버에서만 켜도 충분하지 않고, 클라이언트 요청도 `full` 이어야 출력에 설명이 실립니다.

## 스캔 PDF OCR

스캔본이나 이미지 기반 PDF만 별도로 OCR 강화가 필요할 때만 사용합니다.

```powershell
pwsh .\scripts\docker-run.ps1 -ForceOcr $true -OcrLang "ko,en"
```

```bash
FORCE_OCR=true OCR_LANG=ko,en bash ./scripts/docker-run.sh
```

주의:

- 현재 프로젝트 정책상 PDF는 기본적으로 OCR 전용 경로로 돌리지 않습니다.
- `ForceOcr`는 스캔 PDF 대응을 위한 선택 옵션입니다.
- PNG/JPG의 별도 OCR은 기존 vLLM OpenAI-compatible API 경로를 유지합니다.

## 자주 쓰는 환경값

앱 컨테이너:

- `APP_STORAGE_BASE_DIR=/var/app-data`
- `APP_OPENDATALOADER_HYBRID_ENABLED=true|false`
- `APP_OPENDATALOADER_HYBRID_BACKEND=docling-fast`
- `APP_OPENDATALOADER_HYBRID_MODE=auto|full`
- `APP_OPENDATALOADER_HYBRID_URL=http://jadp-hybrid:5002`
- `APP_OPENDATALOADER_HYBRID_TIMEOUT_MILLIS=120000`
- `APP_OPENDATALOADER_HYBRID_FALLBACK=true|false`
- `APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_REQUESTS=true|false`
- `APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_PII=true|false`
- `APP_OPENDATALOADER_HYBRID_PREFER_FULL_MODE=true|false`

하이브리드 서버 컨테이너:

- `HYBRID_HOST=0.0.0.0`
- `HYBRID_PORT=5002`
- `HYBRID_LOG_LEVEL=info`
- `HYBRID_FORCE_OCR=true|false`
- `HYBRID_OCR_LANG=ko,en`
- `HYBRID_ENRICH_PICTURE_DESCRIPTION=true|false`
- `HYBRID_PICTURE_DESCRIPTION_PROMPT=...`

## 저장 위치

스크립트 기본 경로:

- 앱 산출물: `var/docker-app-data`
- 하이브리드 모델 캐시: `var/docker-hybrid-cache`

하이브리드 모델은 첫 실행 때 시간이 꽤 걸릴 수 있습니다. 캐시 디렉터리를 유지하면 다음 실행부터 훨씬 빠릅니다.

## 수동 docker run 예시

하이브리드 서버:

```bash
docker run -d \
  --name jadp-hybrid \
  --network jadp-net \
  -p 5002:5002 \
  -v "$(pwd)/var/docker-hybrid-cache:/root/.cache" \
  -e HYBRID_HOST=0.0.0.0 \
  -e HYBRID_PORT=5002 \
  -e HYBRID_ENRICH_PICTURE_DESCRIPTION=true \
  jadp-hybrid-cpu:latest
```

앱 서버:

```bash
docker run -d \
  --name jadp-app \
  --network jadp-net \
  -p 8080:8080 \
  -v "$(pwd)/var/docker-app-data:/var/app-data" \
  -e APP_STORAGE_BASE_DIR=/var/app-data \
  -e APP_OPENDATALOADER_HYBRID_ENABLED=true \
  -e APP_OPENDATALOADER_HYBRID_BACKEND=docling-fast \
  -e APP_OPENDATALOADER_HYBRID_MODE=full \
  -e APP_OPENDATALOADER_HYBRID_URL=http://jadp-hybrid:5002 \
  -e APP_OPENDATALOADER_HYBRID_TIMEOUT_MILLIS=120000 \
  -e APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_REQUESTS=true \
  -e APP_OPENDATALOADER_HYBRID_AUTO_APPLY_TO_PII=true \
  -e APP_OPENDATALOADER_HYBRID_PREFER_FULL_MODE=true \
  jadp:latest
```

## 운영 메모

- 복잡한 표 정확도와 이미지가 많은 문서 대응력은 하이브리드가 유리하지만, 공식 문서 기준 속도는 Java-only보다 느려질 수 있습니다.
- 디지털 PDF는 Java 경로만으로 충분한 경우가 많습니다. 대량 단순 문서는 Java-only도 고려하세요.
- picture description은 출력의 설명 품질을 높여 주지만, 처리 시간이 늘고 하이브리드 의존성이 커집니다.
- 하이브리드 백엔드가 죽어도 `hybrid_fallback=true` 면 Java-only 경로로 계속 처리됩니다.

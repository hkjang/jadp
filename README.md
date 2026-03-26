# JADP

Spring Boot 기반 OpenDataLoader PDF 연계 샘플 프로젝트입니다. PDF 업로드 API, 비동기 Job 처리, 동기 미리보기, Swagger 문서, 브라우저 테스트 화면, 결과 파일 다운로드를 함께 제공합니다.

## 포함 기능

- `POST /api/v1/pdf/convert`: 비동기 Job 생성
- `POST /api/v1/pdf/convert-sync`: 소형 문서 동기 변환
- `GET /api/v1/pdf/jobs/{jobId}`: 상태/파일 조회
- `GET /api/v1/pdf/jobs`: 최근 작업 목록
- `GET /api/v1/pdf/files/{fileId}`: 생성 파일 다운로드/미리보기
- `GET /api/v1/pdf/config/options`: UI 옵션 목록
- `GET /swagger-ui.html`: Swagger UI
- `GET /`: 테스트 콘솔
- `GET /docs`: 프로젝트 문서 페이지
- `GET /actuator/health`: 헬스체크

## 기술 메모

- OpenDataLoader Java Quick Start는 `org.opendataloader:opendataloader-pdf-core:1.11.0` 예시를 보여줍니다.
- 공식 사이트의 Javadoc latest 링크는 `2.0.2`를 가리킬 수 있습니다.
- GitHub 공개 릴리즈/README 기준으로는 `1.3.0` 흔적도 함께 보입니다.

위 표면 차이 때문에 이 프로젝트는 기본 버전을 `1.3.0` 으로 두고, 라이브러리 연동부는 `Config` / `OpenDataLoaderPDF` 를 반사 기반으로 호출합니다. 버전을 바꿀 때는 먼저 아래 순서로 검증하는 것을 권장합니다.

1. `pom.xml` 의 `opendataloader.version` 값을 변경합니다.
2. `mvn compile` 과 `mvn test` 를 실행합니다.
3. `/api/v1/pdf/convert-sync` 로 실제 PDF 샘플을 돌려 산출물 생성 여부를 확인합니다.
4. `README` 와 운영 문서에 확정 버전을 기록합니다.

## 요구 환경

- JDK 17
- Maven 3.9+

이 작업공간에서는 시스템 `mvn` 대신 IntelliJ 번들 Maven 경로를 사용해 검증했습니다. 일반 환경에서는 표준 `mvn` 명령으로 실행하면 됩니다.

## 실행

```powershell
mvn -s .mvn-local-settings.xml spring-boot:run
```

의존성 캐시를 작업공간 안에 두려면 그대로 실행하면 되고, 일반 로컬 환경에서 전역 Maven 캐시를 사용하려면 `-s .mvn-local-settings.xml` 없이 실행해도 됩니다.

## 리눅스 실행

Ubuntu 22.04/24.04 또는 그에 준하는 Linux 서버 기준 권장 순서입니다.

### 1. 네이티브 실행

필수 패키지:

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk maven fontconfig fonts-noto-cjk
```

프로젝트 실행:

```bash
mvn -s .mvn-local-settings.xml spring-boot:run
```

운영형 JAR 실행:

```bash
mvn -s .mvn-local-settings.xml -DskipTests package
APP_STORAGE_BASE_DIR=var/app-data java -jar target/jadp-0.0.1-SNAPSHOT.jar
```

메모:

- `fonts-noto-cjk` 는 masked PDF 오버레이의 한글 표시를 위해 권장합니다.
- Linux 에서 시스템 Maven 을 쓴다면 `-s .mvn-local-settings.xml` 없이 실행해도 됩니다.
- 앱 기본 포트는 `8080` 입니다.

### 2. Linux 서버에서 Docker 실행

CPU 하이브리드:

```bash
bash ./scripts/docker-build.sh
bash ./scripts/docker-run.sh
```

GPU 하이브리드:

```bash
HYBRID_TARGET=gpu bash ./scripts/docker-build.sh
HYBRID_VARIANT=gpu bash ./scripts/docker-run.sh
```

이미지/차트 설명까지 켜려면:

```bash
ENABLE_PICTURE_DESCRIPTION=true PREFER_FULL_MODE=true bash ./scripts/docker-run.sh
```

스캔 PDF 에만 OCR 을 강제하려면:

```bash
FORCE_OCR=true OCR_LANG=ko,en bash ./scripts/docker-run.sh
```

### 3. Linux 서버 확인 포인트

- 앱 헬스체크: `curl http://localhost:8080/actuator/health`
- 앱 Swagger: `http://localhost:8080/swagger-ui.html`
- 하이브리드 FastAPI 문서: `http://localhost:5002/docs`

루트 경로 `http://localhost:5002/` 는 `404` 여도 정상입니다. 하이브리드 서버는 Uvicorn/FastAPI 이므로 `/docs` 로 확인하는 것이 맞습니다.

## 테스트

```powershell
mvn -o -s .mvn-local-settings.xml test
```

처음 한 번은 온라인으로 의존성을 내려받아야 할 수 있습니다.

## 샘플 호출

### 비동기 변환

```bash
curl -X POST "http://localhost:8080/api/v1/pdf/convert" \
  -F "file=@sample.pdf" \
  -F "formats=json,markdown,html" \
  -F "pages=1,3,5-7" \
  -F "readingOrder=xycut" \
  -F "tableMethod=default" \
  -F "sanitize=true"
```

### 동기 변환

```bash
curl -X POST "http://localhost:8080/api/v1/pdf/convert-sync" \
  -F "file=@sample.pdf" \
  -F "formats=json,markdown,html"
```

## 저장 구조

- 업로드 파일: `var/app-data/<jobId>/input`
- 산출물: `var/app-data/<jobId>/output`

## 구현 포인트

- 라이브러리 직접 연동 우선
- 비동기 Job 구조와 최근 이력 조회 제공
- 브라우저 테스트 콘솔 제공
- Swagger / OpenAPI 자동 문서화
- Actuator health 및 OpenDataLoader availability 표시
- 텍스트 산출물 후처리 기반 민감정보 마스킹
- 하이브리드 `docling-fast` 백엔드 연계와 Docker 실행 스크립트 제공
- Docker 앱 이미지에 한글 글꼴을 포함해 masked PDF 오버레이 한글 깨짐 보강

## 알려진 주의사항

- OpenDataLoader 버전별 setter 표면이 달라질 수 있어, 새 버전 적용 전 실제 PDF 샘플 회귀 테스트가 필요합니다.
- `sanitize=true` 는 텍스트 계열 산출물 후처리도 함께 수행합니다. 바이너리 PDF 산출물 자체를 재작성하지는 않습니다.
- `hybrid` 옵션은 문서상 백엔드 URL/타임아웃/fallback 을 지원하지만, 실제 운영에서는 별도 하이브리드 서버 준비가 필요합니다.

## Hybrid 개선

공식 하이브리드 문서 기준으로 `docling-fast`, `hybrid_url`, `hybrid_timeout`, `hybrid_fallback`, `hybrid-mode=full` 흐름을 앱 기본 설정과 UI 옵션에 반영했습니다.

- 복잡한 표, 이미지가 많은 PDF, OCR-heavy 문서에 대비해 `app.opendataloader.hybrid.*` 설정을 추가했습니다.
- PDF 변환 요청과 PDF 기반 PII 탐지는 같은 하이브리드 기본값 resolver 를 사용합니다.
- picture description 을 실제 출력까지 가져오려면 하이브리드 서버에서 `--enrich-picture-description`, 앱 요청에서 `hybrid-mode=full` 이 모두 필요합니다.
- 스캔 PDF용 `force-ocr` 는 지원 가능한 운영 옵션으로만 문서화했고, 기본 동작은 계속 비OCR PDF 파이프라인입니다.

주요 설정:

```yaml
app:
  opendataloader:
    hybrid:
      enabled: false
      backend: docling-fast
      mode: auto
      url: http://localhost:5002
      timeout-millis: 60000
      fallback: true
      auto-apply-to-requests: false
      auto-apply-to-pii: true
      prefer-full-mode: false
```

## Docker

Docker 자산을 함께 제공합니다.

- 앱 이미지: [Dockerfile](C:/Users/USER/projects/jadp/Dockerfile)
- 하이브리드 CPU 이미지: [docker/hybrid/Dockerfile.cpu](C:/Users/USER/projects/jadp/docker/hybrid/Dockerfile.cpu)
- 하이브리드 GPU 이미지: [docker/hybrid/Dockerfile.gpu](C:/Users/USER/projects/jadp/docker/hybrid/Dockerfile.gpu)
- 빌드 스크립트: [scripts/docker-build.ps1](C:/Users/USER/projects/jadp/scripts/docker-build.ps1), [scripts/docker-build.sh](C:/Users/USER/projects/jadp/scripts/docker-build.sh)
- 실행 스크립트: [scripts/docker-run.ps1](C:/Users/USER/projects/jadp/scripts/docker-run.ps1), [scripts/docker-run.sh](C:/Users/USER/projects/jadp/scripts/docker-run.sh)
- 운영 문서: [DOCKER.md](C:/Users/USER/projects/jadp/DOCKER.md)

PowerShell 기준:

```powershell
pwsh .\scripts\docker-build.ps1
pwsh .\scripts\docker-run.ps1
```

이미지/차트 설명까지 강화하려면:

```powershell
pwsh .\scripts\docker-run.ps1 -EnablePictureDescription $true -PreferFullMode $true
```

GPU 서버용 하이브리드 이미지를 따로 만들려면:

```powershell
pwsh .\scripts\docker-build.ps1 -SkipApp -HybridTarget gpu
pwsh .\scripts\docker-run.ps1 -HybridVariant gpu
```

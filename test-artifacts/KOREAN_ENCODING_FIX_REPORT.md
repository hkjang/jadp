# Korean Encoding Fix Report

기준일: 2026-03-26

## 수정 내용

- `markdown` 및 `text/html`/`text/plain`/`application/json` 산출물 응답에 `charset=UTF-8` 을 명시하도록 수정
- `masked PDF` 오버레이 텍스트 렌더링 시 한글 표시 가능한 글꼴을 우선 선택하도록 개선
- 한글 글꼴이 없는 환경에서는 깨진 글자 대신 안전한 ASCII 마스킹 텍스트로 fallback
- 앱 Docker 이미지에 `fonts-noto-cjk` 를 추가해 리눅스 컨테이너에서도 한글 마스킹 오버레이가 깨지지 않도록 보강

## 검증 환경

- JADP app container: `jadp:latest`
- Hybrid container: `jadp-hybrid-cpu:latest`
- App URL: `http://localhost:39080`
- Hybrid URL: `http://localhost:36002`

## 검증 결과

### Markdown

샘플 파일:

- `samples/pii/synthetic-korean-pii-full.pdf`

호출:

- `POST /api/v1/pdf/convert-sync`
- `formats=markdown`

확인 결과:

- 다운로드 헤더 `Content-Type: text/markdown;charset=UTF-8`
- 본문에 `PII 종합 샘플 문서`, `홍길동`, `서울 영등포구 국제금융로` 가 한글 그대로 유지됨

증빙 파일:

- `test-artifacts/korean-markdown-response.json`
- `test-artifacts/korean-markdown-headers.txt`
- `test-artifacts/korean-markdown-body.md`

### Masked PDF

샘플 파일:

- `samples/pii/synthetic-korean-pii-full.pdf`

호출:

- `POST /api/v1/pii/mask`

확인 결과:

- masked PDF 다운로드 성공
- 첫 페이지 렌더링 PNG에서 `홍*동`, `서울 영등포구 국제금융로 *****` 가 깨지지 않고 표시됨
- 숫자/이메일/주소 마스킹도 함께 정상 반영

증빙 파일:

- `test-artifacts/masked-korean-response.json`
- `test-artifacts/masked-korean.pdf`
- `test-artifacts/masked-korean-page-1.png`

## 결론

- `markdown 결과 한글 깨짐`은 UTF-8 charset 명시로 재현 불가 상태로 확인
- `masked PDF 한글 깨짐`은 글꼴 fallback + Docker 한글 폰트 추가 후 재현 불가 상태로 확인

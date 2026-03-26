# Korean PDF End-to-End Test Report

- Executed at: 2026-03-26 19:37:33 +09:00
- Application port: 18085
- Raw results JSON: `test-artifacts/korean-e2e-results.json`
- Financial statement sample: `samples/financial-statement.pdf`
- Privacy protection sample: `samples/privacy-protection-guide.pdf`
- Complex museum sample: `samples/korean-complex.pdf`

## Key Findings

- 성공 시나리오 5건이 모두 HTTP 및 최종 job 상태 기준으로 정상 완료되었습니다.
- 요청한 `pages=1-2`와 달리 산출 JSON의 `number of pages`가 baseline과 동일했습니다. 현재 핀된 OpenDataLoader `1.3.0`에서는 페이지 범위 setter가 노출되지 않아 이 옵션이 런타임에 적용되지 않은 것으로 보입니다.
- 개인정보보호법 안내서 전체 포맷 변환은 10.9초가 걸렸고, 부가 이미지 산출물이 327개 생성되어 저장 용량 증가를 확인했습니다.
- sanitize=true 환경에서는 PDF 메타데이터의 생성일/수정일 문자열도 [PHONE]으로 치환되는 과마스킹이 확인되었습니다.
- 이미지 포함 Markdown 시나리오에서는 image 파일 수: 7, markdown 내 이미지 참조 감지: True, 절대 경로 포함: False.
- 잘못된 페이지 범위(pages=1-a)는 HTTP 400로 거절되어 입력 검증이 동작했습니다.

## Scenario Summary

| Scenario | Mode | HTTP | Final Status | Output Files | Image Files | JSON | Hangul | Notes |
| --- | --- | --- | --- | ---: | ---: | --- | --- | --- |
| sync_financial_basic | sync | 200 | SUCCEEDED | 3 | 0 | ok/16p | True | 재무제표 PDF sync 기본 변환 |
| sync_financial_markdown_html | sync | 200 | SUCCEEDED | 2 | 0 | ok/16p | False | 재무제표 PDF에 markdown-with-html 및 struct tree 요청 |
| async_privacy_full_formats | async | 202 | SUCCEEDED | 332 | 327 | ok/151p | True | 개인정보보호 안내 PDF 전체 포맷 생성 |
| async_privacy_pages_subset | async | 202 | SUCCEEDED | 2 | 0 | ok/151p | True | 개인정보보호 PDF 페이지 범위 및 줄바꿈/struct tree 요청 |
| async_museum_markdown_images | async | 202 | SUCCEEDED | 9 | 7 | ok/5p | True | 이미지 포함 Markdown 및 sanitize off |

## Validation Checks

| Check | HTTP | Passed | Response Preview |
| --- | --- | --- | --- |
| invalid_pages_rejected | 400 | True | {"timestamp":"2026-03-26T10:37:33.220175900Z","status":400,"error":"Bad Request","message":"Invalid pages format. Example: 1,3,5-7","path":"/api/v1/pdf/convert"} |

## sync_financial_basic

- Endpoint: `/api/v1/pdf/convert-sync`
- Input file: `financial-statement.pdf`
- Request params: `formats=json,markdown,html, readingOrder=xycut, sanitize=true, tableMethod=default`
- HTTP status: 200
- Final status: SUCCEEDED
- Image artifact count: 0
- JSON summary: pages=16, top-level kids=92, file=financial-statement.pdf, placeholder=True, maskedDateMetadata=True
- Markdown summary: length=9995, hangul=True, html=False, imageRef=False, absPathRef=False, placeholder=False

```text
#### 2024회계연도(제21기)

(2024.3.1 ∼ 2025.2.28)

# 산학협력단 결산서

포항공과대학교 산학협력단

|Ⅰ.현  금  흐  름  표|
|---|


(단위: 원)

####### 관항목항관목항관

- Ⅰ. 현금유입액371,951,202,496326,142,514,420


- 1.운영활동으로 인한 현금유입액342,842,497,678317,118,422,320


- 1)산학협력수익 현금유입액59,392,677,47352,801,269,917

- (1)산학협력연구수익58,162,245,58751,305
```

| Format | Filename | Size | Download URL |
| --- | --- | ---: | --- |
| html | financial-statement.html | 18620 | http://127.0.0.1:18085/api/v1/pdf/files/c8a01536-894a-458c-a61a-01009dc5d0bb |
| json | financial-statement.json | 128774 | http://127.0.0.1:18085/api/v1/pdf/files/d9d9e850-6413-49ea-829c-3ce5877a8e0b |
| markdown | financial-statement.md | 13985 | http://127.0.0.1:18085/api/v1/pdf/files/f8cd85ec-95ff-4f7f-b1ba-71af0939725d |

## sync_financial_markdown_html

- Endpoint: `/api/v1/pdf/convert-sync`
- Input file: `financial-statement.pdf`
- Request params: `formats=json,markdown-with-html, includeHeaderFooter=true, keepLineBreaks=true, sanitize=true, useStructTree=true`
- HTTP status: 200
- Final status: SUCCEEDED
- Image artifact count: 0
- JSON summary: pages=16, top-level kids=7, file=financial-statement.pdf, placeholder=True, maskedDateMetadata=True
- Markdown summary: length=107972, hangul=False, html=True, imageRef=False, absPathRef=False, placeholder=False

```text
<table>
  <tr>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
    <th></th>
  </tr>
  <tr>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    <td></td>
    
```

| Format | Filename | Size | Download URL |
| --- | --- | ---: | --- |
| json | financial-statement.json | 2264869 | http://127.0.0.1:18085/api/v1/pdf/files/42f474c5-c3cc-4fee-a475-7d63f9d00c93 |
| markdown | financial-statement.md | 107972 | http://127.0.0.1:18085/api/v1/pdf/files/f9be357d-2678-4ba4-9232-47926fb2b55f |

## async_privacy_full_formats

- Endpoint: `/api/v1/pdf/convert`
- Input file: `privacy-protection-guide.pdf`
- Request params: `formats=json,markdown,html,pdf,text, readingOrder=xycut, sanitize=true, tableMethod=default`
- HTTP status: 202
- Final status: SUCCEEDED
- Processing millis: 11058
- Image artifact count: 327
- JSON summary: pages=151, top-level kids=1792, file=privacy-protection-guide.pdf, placeholder=True, maskedDateMetadata=True
- Markdown summary: length=141628, hangul=True, html=True, imageRef=False, absPathRef=False, placeholder=True

```text
##### 목차

|업무내용|페이지|
|---|---|
|￭각급학교개인정보보호업무연간일정|1|
|￭개인정보보호법해설|3|
|1.개인정보내부관리계획개정및이행실태점검<br><br>-내부관리계획개정기안문(개정사항이있는경우)<br>-내부관리계획표준안(예시)<br>-내부관리계획검토결과보고서기안문(개정사항이없는경우)<br>-내부관리계획이행실태점검결과기안문및이행실태점검표(예시)<br>|22|
|2.개인정보처리방침개정및홈페이지공개<br><br>-개인정보처리방침정비기안문(예시)<br>-개인정보처리방침표준안(예시)<br>|54|
|3.개인정보파일일제
```

| Format | Filename | Size | Download URL |
| --- | --- | ---: | --- |
| html | privacy-protection-guide.html | 414656 | http://127.0.0.1:18085/api/v1/pdf/files/b48bab66-4dab-48f5-ade3-ed5b015e57f1 |
| json | privacy-protection-guide.json | 2825193 | http://127.0.0.1:18085/api/v1/pdf/files/3cd98bd0-c7b0-442a-b16a-a35a61041f70 |
| markdown | privacy-protection-guide.md | 336859 | http://127.0.0.1:18085/api/v1/pdf/files/1cf88e60-1a69-43d1-9b63-614a486d1a6d |
| text | privacy-protection-guide.txt | 197732 | http://127.0.0.1:18085/api/v1/pdf/files/a7c36c83-0068-4034-a4a6-0527b4d0cb5f |
| pdf | privacy-protection-guide_annotated.pdf | 3232931 | http://127.0.0.1:18085/api/v1/pdf/files/5d309c48-91c4-4c2c-8941-9e6da831703c |
| image | figure1.png | 3181 | http://127.0.0.1:18085/api/v1/pdf/files/4de27c05-7bab-4f5a-b61d-1bbb0f5bf1e4 |
| image | figure10.png | 91487 | http://127.0.0.1:18085/api/v1/pdf/files/14d93c9a-ed81-4225-bd7f-b3b2ad86a58e |
| image | figure100.png | 2059 | http://127.0.0.1:18085/api/v1/pdf/files/4f018fbd-1bc1-4cb4-807f-157dc8857892 |
| image | figure101.png | 3126 | http://127.0.0.1:18085/api/v1/pdf/files/6b49309d-0b92-49f5-819f-a51af6a1f046 |
| image | figure102.png | 4315 | http://127.0.0.1:18085/api/v1/pdf/files/fece491b-0f89-4332-8cd6-400578035c2b |

- Additional image files omitted from table: 322

## async_privacy_pages_subset

- Endpoint: `/api/v1/pdf/convert`
- Input file: `privacy-protection-guide.pdf`
- Request params: `formats=json,markdown, keepLineBreaks=true, pages=1-2, sanitize=true, useStructTree=true`
- HTTP status: 202
- Final status: SUCCEEDED
- Processing millis: 5972
- Image artifact count: 0
- JSON summary: pages=151, top-level kids=1792, file=privacy-protection-guide.pdf, placeholder=True, maskedDateMetadata=True
- Markdown summary: length=144971, hangul=True, html=True, imageRef=False, absPathRef=False, placeholder=True

```text
##### 목차

|업무내용|페이지|
|---|---|
|￭각급학교개인정보보호업무연간일정|1|
|￭개인정보보호법해설|3|
|1.개인정보내부관리계획개정및이행실태점검<br><br>-내부관리계획개정기안문(개정사항이있는경우)<br>-내부관리계획표준안(예시)<br>-내부관리계획검토결과보고서기안문(개정사항이없는경우)<br>-내부관리계획이행실태점검결과기안문및이행실태점검표(예시)<br>|22|
|2.개인정보처리방침개정및홈페이지공개<br><br>-개인정보처리방침정비기안문(예시)<br>-개인정보처리방침표준안(예시)<br>|54|
|3.개인정보파일일제
```

| Format | Filename | Size | Download URL |
| --- | --- | ---: | --- |
| json | privacy-protection-guide.json | 2825163 | http://127.0.0.1:18085/api/v1/pdf/files/d0b5366d-a047-4f30-b1f3-2744c51f1388 |
| markdown | privacy-protection-guide.md | 339990 | http://127.0.0.1:18085/api/v1/pdf/files/a0fa01a5-1de9-4d41-9e9d-37a9e2e5c1e8 |

## async_museum_markdown_images

- Endpoint: `/api/v1/pdf/convert`
- Input file: `korean-complex.pdf`
- Request params: `formats=json,markdown-with-images, imageFormat=png, imageOutput=external, sanitize=false`
- HTTP status: 202
- Final status: SUCCEEDED
- Processing millis: 1403
- Image artifact count: 7
- JSON summary: pages=5, top-level kids=70, file=korean-complex.pdf, placeholder=False, maskedDateMetadata=False
- Markdown summary: length=5556, hangul=True, html=False, imageRef=True, absPathRef=False, placeholder=False

```text
Conservation  Science  in  Museum  Vol.  14,  1~5  (2013)

![image 1](/api/v1/pdf/files/259ba078-ddff-4ea8-afaa-0abebf349093)

## 공주수촌리출토칠기칼집의보존

이용희1,  연정아1,  박정혜1,  김수철2,*

- 1국립중앙박물관보존과학부
- 2한국전통문화대학교보존과학과


# Conservation of the Lacquered Sheath Excavated from Soochon-ri, Gongju, Korea

Lee  Yon
```

| Format | Filename | Size | Download URL |
| --- | --- | ---: | --- |
| json | korean-complex.json | 31801 | http://127.0.0.1:18085/api/v1/pdf/files/51d2a3e0-53f3-467a-9fd9-b0f499cff02c |
| markdown | korean-complex.md | 10322 | http://127.0.0.1:18085/api/v1/pdf/files/14c7545d-bcde-420b-8374-f97d1c2d1547 |
| image | imageFile1.png | 1748 | http://127.0.0.1:18085/api/v1/pdf/files/259ba078-ddff-4ea8-afaa-0abebf349093 |
| image | imageFile2.png | 395216 | http://127.0.0.1:18085/api/v1/pdf/files/78e08af0-969a-4143-a0c4-402585f38df2 |
| image | imageFile3.png | 1193654 | http://127.0.0.1:18085/api/v1/pdf/files/2adce200-57ef-4b91-99bf-b933f4c2df15 |
| image | imageFile4.png | 276619 | http://127.0.0.1:18085/api/v1/pdf/files/ab0310c6-ca1b-4d7b-bb0f-f5dd5ca1fba0 |
| image | imageFile5.png | 405793 | http://127.0.0.1:18085/api/v1/pdf/files/28191c16-0410-4850-aa3d-00aafe1160db |

- Additional image files omitted from table: 2


# PII Detection and Masking Report

- Executed at: 2026-03-26 20:12:34 +09:00
- Test port: 18086
- Raw results JSON: `test-artifacts/pii-detection-results.json`
- Note: PII positive tests use synthetic Korean documents to avoid using exposed real-person personal data.

## Key Findings

- 합성 한글 PDF 1건에서 탐지된 PII 유형 수: 12 / 12
- 누락 유형: 없음
- 다중 페이지 PDF는 페이지 집합 1, 2 에서 탐지되어 페이지 번호/bounding box 응답이 동작했습니다.
- 마스킹 후 재탐지 결과 건수: 0. PDF는 비OCR 경로 기준으로 원문 텍스트가 남지 않도록 라스터 masked PDF로 생성했습니다.
- 공개 개인정보보호 안내서 baseline 탐지 건수: 15
- 공개 재무제표 baseline 탐지 건수: 0
- PNG vLLM 경로: SKIPPED - 사용자 요청에 따라 PNG vLLM OCR 경로는 구현만 하고, vLLM endpoint 미구성 상태에서 실행 검증은 생략했습니다.

## Scenario Summary

| Scenario | File | Findings | Types | Pages | Notes |
| --- | --- | ---: | --- | --- | --- |
| synthetic_full_detect | samples\\pii\\synthetic-korean-pii-full.pdf | 12 | BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER, DRIVER_LICENSE_NUMBER, EMAIL_ADDRESS, FOREIGNER_REGISTRATION_NUMBER, IP_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER, PASSPORT_NUMBER, PERSON_NAME, RESIDENT_REGISTRATION_NUMBER, STREET_ADDRESS | 1 | 12개 기준 유형이 모두 들어간 합성 한글 PDF |
| synthetic_multipage_detect | samples\\pii\\synthetic-korean-pii-multipage.pdf | 12 | BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER, DRIVER_LICENSE_NUMBER, EMAIL_ADDRESS, FOREIGNER_REGISTRATION_NUMBER, IP_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER, PASSPORT_NUMBER, PERSON_NAME, RESIDENT_REGISTRATION_NUMBER, STREET_ADDRESS | 1, 2 | 다중 페이지 합성 한글 PDF |
| privacy_guide_negative_control | samples\\privacy-protection-guide.pdf | 15 | EMAIL_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER | 54, 66, 134, 138, 140 | 공개 개인정보보호 안내서 baseline |
| financial_statement_negative_control | samples\\financial-statement.pdf | 0 |  |  | 공개 재무제표 샘플 baseline |
| korean_complex_negative_control | samples\\korean-complex.pdf | 1 | EMAIL_ADDRESS | 1 | 공개 한글 복합 PDF baseline |
| synthetic_full_mask | samples\\pii\\synthetic-korean-pii-full.pdf | 12 | BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER, DRIVER_LICENSE_NUMBER, EMAIL_ADDRESS, FOREIGNER_REGISTRATION_NUMBER, IP_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER, PASSPORT_NUMBER, PERSON_NAME, RESIDENT_REGISTRATION_NUMBER, STREET_ADDRESS | 1 | 마스킹 API 호출 후 masked PDF 재탐지 |

## API Validation

| Scenario | Expected | Actual | Notes |
| --- | ---: | ---: | --- |
| detect_unsupported_text_file | 415 | 415 | Only PDF, PNG, JPG, JPEG are supported. |
| download_missing_masked_file | 404 | 404 | Unknown artifact id should return not found. |

## synthetic_full_detect

- File: `samples\\pii\\synthetic-korean-pii-full.pdf`
- Finding count: 12
- Types: BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER, DRIVER_LICENSE_NUMBER, EMAIL_ADDRESS, FOREIGNER_REGISTRATION_NUMBER, IP_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER, PASSPORT_NUMBER, PERSON_NAME, RESIDENT_REGISTRATION_NUMBER, STREET_ADDRESS
- Pages: 1
- Notes: 12개 기준 유형이 모두 들어간 합성 한글 PDF

| Type | Original | Masked | Page | Bounding Box | Source |
| --- | --- | --- | ---: | --- | --- |
| STREET_ADDRESS | 서울 영등포구 국제금융로 10 3층 | 서울 영등포구 국제금융로 ***** | 1 | x=50, y=508.616, w=246.848, h=23.5862307692308 | pdf-structured |
| IP_ADDRESS | 192.168.254.123 | 192.168.***.123 | 1 | x=50, y=532.202230769231, w=246.848, h=23.5862307692308 | pdf-structured |
| EMAIL_ADDRESS | abcdefg@naver.com | abc****@naver.com | 1 | x=50, y=555.788461538462, w=246.848, h=23.5862307692308 | pdf-structured |
| BANK_ACCOUNT_NUMBER | 123-45-6789-012 | ***-**-****-012 | 1 | x=50, y=579.374692307692, w=246.848, h=23.5862307692308 | pdf-structured |
| CREDIT_CARD_NUMBER | 4111-1111-1111-1111 | ****-****-****-1111 | 1 | x=50, y=602.960923076923, w=246.848, h=23.5862307692308 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-1234-5678 | 02-1234-**** | 1 | x=50, y=626.547153846154, w=246.848, h=23.5862307692308 | pdf-structured |
| MOBILE_PHONE_NUMBER | 010-1234-5678 | 010-1234-**** | 1 | x=50, y=650.133384615385, w=246.848, h=23.5862307692308 | pdf-structured |
| FOREIGNER_REGISTRATION_NUMBER | 123456-5123456 | 123456-******* | 1 | x=50, y=673.719615384615, w=246.848, h=23.5862307692308 | pdf-structured |
| PASSPORT_NUMBER | M12345678 | M1234**** | 1 | x=50, y=697.305846153846, w=246.848, h=23.5862307692308 | pdf-structured |
| DRIVER_LICENSE_NUMBER | 11-24-123456-62 | 11-24-******-62 | 1 | x=50, y=720.892076923077, w=246.848, h=23.5862307692308 | pdf-structured |
| RESIDENT_REGISTRATION_NUMBER | 800901-1234567 | 800901-******* | 1 | x=50, y=744.478307692308, w=246.848, h=23.5862307692308 | pdf-structured |
| PERSON_NAME | 홍길동 | 홍*동 | 1 | x=50, y=768.064538461538, w=246.848, h=23.5862307692308 | pdf-structured |

## synthetic_multipage_detect

- File: `samples\\pii\\synthetic-korean-pii-multipage.pdf`
- Finding count: 12
- Types: BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER, DRIVER_LICENSE_NUMBER, EMAIL_ADDRESS, FOREIGNER_REGISTRATION_NUMBER, IP_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER, PASSPORT_NUMBER, PERSON_NAME, RESIDENT_REGISTRATION_NUMBER, STREET_ADDRESS
- Pages: 1, 2
- Notes: 다중 페이지 합성 한글 PDF

| Type | Original | Masked | Page | Bounding Box | Source |
| --- | --- | --- | ---: | --- | --- |
| BANK_ACCOUNT_NUMBER | 987-65-4321-001 | ***-**-****-001 | 1 | x=50, y=676.616, w=173.782, h=22.9242 | pdf-structured |
| EMAIL_ADDRESS | test@korea.kr | ****@korea.kr | 1 | x=50, y=699.5402, w=173.782, h=22.9242 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 031-555-1234 | 031-555-**** | 1 | x=50, y=722.4644, w=173.782, h=22.9242 | pdf-structured |
| MOBILE_PHONE_NUMBER | 010 9876 5432 | 010-9876-**** | 1 | x=50, y=745.3886, w=173.782, h=22.9242 | pdf-structured |
| PERSON_NAME | 선우용녀 | 선*용* | 1 | x=50, y=768.3128, w=173.782, h=22.9242 | pdf-structured |
| IP_ADDRESS | 10.20.30.40 | 10.20.***.40 | 2 | x=50, y=628.616, w=298.13, h=23.2315714285714 | pdf-structured |
| CREDIT_CARD_NUMBER | 4012-8888-8888-1881 | ****-****-****-1881 | 2 | x=50, y=651.847571428571, w=298.13, h=23.2315714285714 | pdf-structured |
| FOREIGNER_REGISTRATION_NUMBER | 770707-7123456 | 770707-******* | 2 | x=50, y=675.079142857143, w=298.13, h=23.2315714285714 | pdf-structured |
| RESIDENT_REGISTRATION_NUMBER | 900101-2345678 | 900101-******* | 2 | x=50, y=698.310714285714, w=298.13, h=23.2315714285714 | pdf-structured |
| DRIVER_LICENSE_NUMBER | 26-11-654321-33 | 26-11-******-33 | 2 | x=50, y=721.542285714286, w=298.13, h=23.2315714285714 | pdf-structured |
| PASSPORT_NUMBER | S76543210 | S7654**** | 2 | x=50, y=744.773857142857, w=298.13, h=23.2315714285714 | pdf-structured |
| STREET_ADDRESS | 부산 해운대구 센텀중앙로 97 A동 1203호 | 부산 해운대구 센텀중앙로 ***** | 2 | x=50, y=768.005428571429, w=298.13, h=23.2315714285714 | pdf-structured |

## privacy_guide_negative_control

- File: `samples\\privacy-protection-guide.pdf`
- Finding count: 15
- Types: EMAIL_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER
- Pages: 54, 66, 134, 138, 140
- Notes: 공개 개인정보보호 안내서 baseline

| Type | Original | Masked | Page | Bounding Box | Source |
| --- | --- | --- | ---: | --- | --- |
| LANDLINE_PHONE_NUMBER | 02-234-5678 | 02-234-**** | 54 | x=442.977, y=91.528, w=55.8800000000001, h=11.952 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-123-1234 | 02-123-**** | 54 | x=464.701, y=159.82, w=53.54, h=11.7 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-123-1234 | 02-123-**** | 54 | x=464.701, y=184.66, w=53.54, h=11.7 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-123-1234 | 02-123-**** | 54 | x=464.701, y=209.5, w=53.54, h=11.7 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-124-2345 | 02-124-**** | 54 | x=177.181, y=275.62, w=281.628, h=11.7 | pdf-structured |
| EMAIL_ADDRESS | abcd@efgf.co.kr | ****@efgf.co.kr | 54 | x=177.181, y=275.62, w=281.628, h=11.7 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-000-0000 | 02-000-**** | 66 | x=82.8, y=290.256, w=166.56, h=77.4570000000001 | pdf-structured |
| MOBILE_PHONE_NUMBER | 010-1234 -4567 | 010-1234-**** | 134 | x=341.245, y=455.572, w=51.04, h=41.028 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 000-123-0000 | 000-123-**** | 138 | x=75.72, y=129.412, w=228.241, h=12.948 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-234-5678 | 02-234-**** | 140 | x=442.977, y=99.808, w=55.8800000000001, h=11.952 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-123-1234 | 02-123-**** | 140 | x=464.701, y=168.1, w=53.54, h=11.7 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-123-1234 | 02-123-**** | 140 | x=464.701, y=192.94, w=53.54, h=11.7 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-123-1234 | 02-123-**** | 140 | x=464.701, y=217.78, w=53.54, h=11.7 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-124-2345 | 02-124-**** | 140 | x=177.181, y=283.78, w=281.628, h=11.7 | pdf-structured |
| EMAIL_ADDRESS | abcd@efgf.co.kr | ****@efgf.co.kr | 140 | x=177.181, y=283.78, w=281.628, h=11.7 | pdf-structured |

## financial_statement_negative_control

- File: `samples\\financial-statement.pdf`
- Finding count: 0
- Types: 
- Pages: 
- Notes: 공개 재무제표 샘플 baseline

## korean_complex_negative_control

- File: `samples\\korean-complex.pdf`
- Finding count: 1
- Types: EMAIL_ADDRESS
- Pages: 1
- Notes: 공개 한글 복합 PDF baseline

| Type | Original | Masked | Page | Bounding Box | Source |
| --- | --- | --- | ---: | --- | --- |
| EMAIL_ADDRESS | oldforest@korea.kr | old******@korea.kr | 1 | x=45.24, y=72.621, w=189.292, h=18.416 | pdf-structured |

## synthetic_full_mask

- File: `samples\\pii\\synthetic-korean-pii-full.pdf`
- Finding count: 12
- Types: BANK_ACCOUNT_NUMBER, CREDIT_CARD_NUMBER, DRIVER_LICENSE_NUMBER, EMAIL_ADDRESS, FOREIGNER_REGISTRATION_NUMBER, IP_ADDRESS, LANDLINE_PHONE_NUMBER, MOBILE_PHONE_NUMBER, PASSPORT_NUMBER, PERSON_NAME, RESIDENT_REGISTRATION_NUMBER, STREET_ADDRESS
- Pages: 1
- Masked download URL: http://127.0.0.1:18086/api/v1/pii/files/6d4012f5-f862-4411-8524-aed4d9d74b5c
- Re-detect finding count after masking: 0
- Notes: 마스킹 API 호출 후 masked PDF 재탐지

| Type | Original | Masked | Page | Bounding Box | Source |
| --- | --- | --- | ---: | --- | --- |
| STREET_ADDRESS | 서울 영등포구 국제금융로 10 3층 | 서울 영등포구 국제금융로 ***** | 1 | x=50, y=508.616, w=246.848, h=23.5862307692308 | pdf-structured |
| IP_ADDRESS | 192.168.254.123 | 192.168.***.123 | 1 | x=50, y=532.202230769231, w=246.848, h=23.5862307692308 | pdf-structured |
| EMAIL_ADDRESS | abcdefg@naver.com | abc****@naver.com | 1 | x=50, y=555.788461538462, w=246.848, h=23.5862307692308 | pdf-structured |
| BANK_ACCOUNT_NUMBER | 123-45-6789-012 | ***-**-****-012 | 1 | x=50, y=579.374692307692, w=246.848, h=23.5862307692308 | pdf-structured |
| CREDIT_CARD_NUMBER | 4111-1111-1111-1111 | ****-****-****-1111 | 1 | x=50, y=602.960923076923, w=246.848, h=23.5862307692308 | pdf-structured |
| LANDLINE_PHONE_NUMBER | 02-1234-5678 | 02-1234-**** | 1 | x=50, y=626.547153846154, w=246.848, h=23.5862307692308 | pdf-structured |
| MOBILE_PHONE_NUMBER | 010-1234-5678 | 010-1234-**** | 1 | x=50, y=650.133384615385, w=246.848, h=23.5862307692308 | pdf-structured |
| FOREIGNER_REGISTRATION_NUMBER | 123456-5123456 | 123456-******* | 1 | x=50, y=673.719615384615, w=246.848, h=23.5862307692308 | pdf-structured |
| PASSPORT_NUMBER | M12345678 | M1234**** | 1 | x=50, y=697.305846153846, w=246.848, h=23.5862307692308 | pdf-structured |
| DRIVER_LICENSE_NUMBER | 11-24-123456-62 | 11-24-******-62 | 1 | x=50, y=720.892076923077, w=246.848, h=23.5862307692308 | pdf-structured |
| RESIDENT_REGISTRATION_NUMBER | 800901-1234567 | 800901-******* | 1 | x=50, y=744.478307692308, w=246.848, h=23.5862307692308 | pdf-structured |
| PERSON_NAME | 홍길동 | 홍*동 | 1 | x=50, y=768.064538461538, w=246.848, h=23.5862307692308 | pdf-structured |

## PNG Note

- Status: SKIPPED
- Detail: 사용자 요청에 따라 PNG vLLM OCR 경로는 구현만 하고, vLLM endpoint 미구성 상태에서 실행 검증은 생략했습니다.

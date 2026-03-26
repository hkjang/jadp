package com.example.jadp.support;

import com.example.jadp.model.PiiType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiPatternMatcherTest {

    @Test
    void detectsStructuredKoreanPiiLabels() {
        List<PiiTextMatch> matches = PiiPatternMatcher.findMatches("""
                성명: 홍길동
                주민등록번호: 800901-1234567
                운전면허번호: 11-24-123456-62
                여권번호: M12345678
                외국인등록번호: 123456-5123456
                휴대폰번호: 010-1234-5678
                전화번호: 02-1234-5678
                신용카드번호: 4111-1111-1111-1111
                계좌번호: 123-45-6789-012
                이메일: abcdefg@naver.com
                IP주소: 192.168.254.123
                주소: 서울 영등포구 국제금융로 10 3층
                """);

        assertThat(matches).extracting(PiiTextMatch::type).containsExactlyInAnyOrder(
                PiiType.PERSON_NAME,
                PiiType.RESIDENT_REGISTRATION_NUMBER,
                PiiType.DRIVER_LICENSE_NUMBER,
                PiiType.PASSPORT_NUMBER,
                PiiType.FOREIGNER_REGISTRATION_NUMBER,
                PiiType.MOBILE_PHONE_NUMBER,
                PiiType.LANDLINE_PHONE_NUMBER,
                PiiType.CREDIT_CARD_NUMBER,
                PiiType.BANK_ACCOUNT_NUMBER,
                PiiType.EMAIL_ADDRESS,
                PiiType.IP_ADDRESS,
                PiiType.STREET_ADDRESS
        );
    }

    @Test
    void avoidsLooseFalsePositivesForNamesAndAddresses() {
        assertThat(PiiPatternMatcher.findMatches("기관명: 대표자성명:(인)")).isEmpty();
        assertThat(PiiPatternMatcher.findMatches("사업장주소,전화번호,담당부서")).isEmpty();
        assertThat(PiiPatternMatcher.findMatches("또는 개인정보 여부")).isEmpty();
    }
}

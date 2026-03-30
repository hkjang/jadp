package com.example.jadp.support;

import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiiFindingMergeSupportTest {

    @Test
    void mergesNearDuplicateFindingsButKeepsDistinctOnes() {
        PiiFinding baseline = new PiiFinding(
                PiiType.MOBILE_PHONE_NUMBER,
                "휴대폰번호",
                "010-1234-5678",
                "010-1234-****",
                1,
                new PiiBoundingBox(100, 200, 120, 20),
                "hybrid"
        );
        PiiFinding duplicate = new PiiFinding(
                PiiType.MOBILE_PHONE_NUMBER,
                "휴대폰번호",
                "010-1234-5678",
                "010-1234-****",
                1,
                new PiiBoundingBox(104, 204, 118, 20),
                "vision"
        );
        PiiFinding distinct = new PiiFinding(
                PiiType.EMAIL_ADDRESS,
                "이메일",
                "hong@example.com",
                "hon****@example.com",
                1,
                new PiiBoundingBox(320, 240, 180, 24),
                "vision"
        );

        List<PiiFinding> merged = PiiFindingMergeSupport.mergeFindings(
                List.of(baseline),
                List.of(duplicate, distinct)
        );

        assertThat(merged).hasSize(2);
        assertThat(merged).extracting(PiiFinding::type)
                .containsExactly(PiiType.MOBILE_PHONE_NUMBER, PiiType.EMAIL_ADDRESS);
    }
}

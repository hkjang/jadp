package com.example.jadp.service;

import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiType;
import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.config.HybridProcessingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PdfStructuredPiiDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void splitsStructuredParagraphIntoFieldLevelBoundingBoxes() throws IOException {
        PdfConversionEngine engine = new StubEngine() {
            @Override
            public void convert(Path inputFile, Path outputDirectory, PdfConversionOptions options) {
                try {
                    Files.createDirectories(outputDirectory);
                    Files.writeString(outputDirectory.resolve("structured.json"), """
                            {
                              "file name": "structured.pdf",
                              "number of pages": 1,
                              "kids": [
                                {
                                  "type": "paragraph",
                                  "page number": 1,
                                  "bounding box": [50.0, 500.0, 300.0, 812.0],
                                  "content": "PII 문서 성명: 홍길동 주민등록번호: 800901-1234567 외국인등록번호: 123456-5123456 주소: 서울 영등포구 국제금융로 10 3층"
                                }
                              ]
                            }
                            """);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        PdfStructuredPiiDetector detector = new PdfStructuredPiiDetector(
                engine,
                new HybridOptionsResolver(new HybridProcessingProperties()),
                new ObjectMapper()
        );
        Path sourceFile = Files.writeString(tempDir.resolve("sample.pdf"), "dummy");

        PiiDetectionResult result = detector.detect(
                UUID.randomUUID(),
                "sample.pdf",
                "application/pdf",
                sourceFile,
                tempDir
        );

        assertThat(result.findings()).extracting(PiiFinding::type).contains(
                PiiType.PERSON_NAME,
                PiiType.RESIDENT_REGISTRATION_NUMBER,
                PiiType.FOREIGNER_REGISTRATION_NUMBER,
                PiiType.STREET_ADDRESS
        );

        PiiFinding resident = result.findings().stream()
                .filter(finding -> finding.type() == PiiType.RESIDENT_REGISTRATION_NUMBER)
                .findFirst()
                .orElseThrow();
        PiiFinding foreigner = result.findings().stream()
                .filter(finding -> finding.type() == PiiType.FOREIGNER_REGISTRATION_NUMBER)
                .findFirst()
                .orElseThrow();

        assertThat(resident.pageNumber()).isEqualTo(1);
        assertThat(foreigner.pageNumber()).isEqualTo(1);
        assertThat(resident.boundingBox().y()).isNotEqualTo(foreigner.boundingBox().y());
        assertThat(resident.originalText()).isEqualTo("800901-1234567");
        assertThat(foreigner.originalText()).isEqualTo("123456-5123456");
    }

    private abstract static class StubEngine implements PdfConversionEngine {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getEngineName() {
            return "stub";
        }

        @Override
        public String getAvailabilityMessage() {
            return "stub";
        }
    }
}

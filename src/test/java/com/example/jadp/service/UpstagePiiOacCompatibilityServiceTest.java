package com.example.jadp.service;

import com.example.jadp.dto.UpstagePiiOacResponse;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiMaskingResult;
import com.example.jadp.model.PiiType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpstagePiiOacCompatibilityServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void detectMapsPdfCoordinatesToOacVertices() throws Exception {
        PiiDocumentService piiDocumentService = mock(PiiDocumentService.class);
        UpstagePiiOacCompatibilityService service = new UpstagePiiOacCompatibilityService(piiDocumentService);

        Path pdf = createPdf(tempDir.resolve("sample.pdf"), 200, 400);
        PiiDetectionResult detectionResult = new PiiDetectionResult(
                UUID.randomUUID(),
                "sample.pdf",
                "application/pdf",
                "pdf",
                1,
                pdf,
                List.of(new PiiFinding(
                        PiiType.RESIDENT_REGISTRATION_NUMBER,
                        "주민등록번호",
                        "800101-1234567",
                        "800101-*******",
                        1,
                        new PiiBoundingBox(20, 250, 80, 30),
                        "pdf-structured"
                ))
        );
        when(piiDocumentService.detect(any(), eq(true))).thenReturn(detectionResult);

        UpstagePiiOacResponse response = service.detect(new MockMultipartFile(
                "document",
                "sample.pdf",
                "application/pdf",
                new byte[]{1}
        ), true);

        assertThat(response.schema()).isEqualTo("oac");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).boundingBoxes().get(0).vertices())
                .extracting(UpstagePiiOacResponse.Vertex::x, UpstagePiiOacResponse.Vertex::y)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(20.0, 120.0),
                        org.assertj.core.groups.Tuple.tuple(100.0, 120.0),
                        org.assertj.core.groups.Tuple.tuple(100.0, 150.0),
                        org.assertj.core.groups.Tuple.tuple(20.0, 150.0)
                );
    }

    @Test
    void maskIncludesMaskedDocumentLink() throws Exception {
        PiiDocumentService piiDocumentService = mock(PiiDocumentService.class);
        UpstagePiiOacCompatibilityService service = new UpstagePiiOacCompatibilityService(piiDocumentService);

        Path pdf = createPdf(tempDir.resolve("sample.pdf"), 200, 400);
        Path masked = Files.writeString(tempDir.resolve("masked.pdf"), "masked");
        PiiDetectionResult detectionResult = new PiiDetectionResult(
                UUID.randomUUID(),
                "sample.pdf",
                "application/pdf",
                "pdf",
                1,
                pdf,
                List.of()
        );
        GeneratedArtifact artifact = new GeneratedArtifact(
                "artifact-1",
                "pdf",
                "sample-masked.pdf",
                "application/pdf",
                Files.size(masked),
                "sample-masked.pdf",
                masked
        );
        when(piiDocumentService.mask(any(), eq(true))).thenReturn(new PiiMaskingResult(detectionResult, artifact));

        UpstagePiiOacResponse response = service.mask(new MockMultipartFile(
                "document",
                "sample.pdf",
                "application/pdf",
                new byte[]{1}
        ), true);

        assertThat(response.maskedDocument()).isNotNull();
        assertThat(response.maskedDocument().fileId()).isEqualTo("artifact-1");
        assertThat(response.maskedDocument().downloadUrl()).isEqualTo("/api/v1/pii/files/artifact-1");
    }

    @Test
    void detectClampsBoundingBoxesToPageBounds() throws Exception {
        PiiDocumentService piiDocumentService = mock(PiiDocumentService.class);
        UpstagePiiOacCompatibilityService service = new UpstagePiiOacCompatibilityService(piiDocumentService);

        Path pdf = createPdf(tempDir.resolve("sample-clamp.pdf"), 200, 400);
        PiiDetectionResult detectionResult = new PiiDetectionResult(
                UUID.randomUUID(),
                "sample.pdf",
                "application/pdf",
                "pdf",
                1,
                pdf,
                List.of(new PiiFinding(
                        PiiType.MOBILE_PHONE_NUMBER,
                        "휴대폰번호",
                        "010-1234-5678",
                        "010-1234-****",
                        1,
                        new PiiBoundingBox(190, -20, 80, 60),
                        "hybrid-direct-table-page-ocr"
                ))
        );
        when(piiDocumentService.detect(any(), eq(true))).thenReturn(detectionResult);

        UpstagePiiOacResponse response = service.detect(new MockMultipartFile(
                "document",
                "sample.pdf",
                "application/pdf",
                new byte[]{1}
        ), true);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).boundingBoxes().get(0).vertices())
                .extracting(UpstagePiiOacResponse.Vertex::x, UpstagePiiOacResponse.Vertex::y)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(190.0, 360.0),
                        org.assertj.core.groups.Tuple.tuple(200.0, 360.0),
                        org.assertj.core.groups.Tuple.tuple(200.0, 400.0),
                        org.assertj.core.groups.Tuple.tuple(190.0, 400.0)
                );
    }

    private Path createPdf(Path target, float width, float height) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(width, height)));
            document.save(target.toFile());
        }
        return target;
    }
}

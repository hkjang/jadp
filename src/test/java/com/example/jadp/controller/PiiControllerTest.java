package com.example.jadp.controller;

import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiMaskingResult;
import com.example.jadp.model.PiiType;
import com.example.jadp.service.PiiDocumentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PiiController.class)
@Import(GlobalExceptionHandler.class)
class PiiControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    PiiDocumentService piiDocumentService;

    @TempDir
    Path tempDir;

    @Test
    void detectReturnsFindingsWithBoundingBoxes() throws Exception {
        PiiDetectionResult detectionResult = new PiiDetectionResult(
                UUID.randomUUID(),
                "sample.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "pdf",
                1,
                tempDir.resolve("sample.pdf"),
                List.of(new PiiFinding(
                        PiiType.MOBILE_PHONE_NUMBER,
                        "휴대폰번호",
                        "010-1234-5678",
                        "010-1234-****",
                        1,
                        new PiiBoundingBox(10, 20, 100, 18),
                        "pdf-structured"
                ))
        );

        when(piiDocumentService.detect(any())).thenReturn(detectionResult);

        mockMvc.perform(multipart("/api/v1/pii/detect")
                        .file(new MockMultipartFile("file", "sample.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(detectionResult.documentId().toString()))
                .andExpect(jsonPath("$.findingCount").value(1))
                .andExpect(jsonPath("$.findings[0].type").value("MOBILE_PHONE_NUMBER"))
                .andExpect(jsonPath("$.findings[0].boundingBox.x").value(10.0))
                .andExpect(jsonPath("$.findings[0].boundingBox.height").value(18.0));
    }

    @Test
    void maskReturnsDownloadUrlAndDownloadStreamsArtifact() throws Exception {
        Path maskedFile = Files.writeString(tempDir.resolve("masked.pdf"), "masked");
        PiiDetectionResult detectionResult = new PiiDetectionResult(
                UUID.randomUUID(),
                "sample.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "pdf",
                1,
                tempDir.resolve("sample.pdf"),
                List.of()
        );
        GeneratedArtifact artifact = new GeneratedArtifact(
                "artifact-1",
                "pdf",
                "sample-masked.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                Files.size(maskedFile),
                "sample-masked.pdf",
                maskedFile
        );

        when(piiDocumentService.mask(any())).thenReturn(new PiiMaskingResult(detectionResult, artifact));
        when(piiDocumentService.getArtifact("artifact-1")).thenReturn(artifact);

        mockMvc.perform(multipart("/api/v1/pii/mask")
                        .file(new MockMultipartFile("file", "sample.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedFileId").value("artifact-1"))
                .andExpect(jsonPath("$.maskedFilename").value("sample-masked.pdf"))
                .andExpect(jsonPath("$.maskedDownloadUrl").value("http://localhost/api/v1/pii/files/artifact-1"));

        mockMvc.perform(get("/api/v1/pii/files/artifact-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sample-masked.pdf")));
    }
}

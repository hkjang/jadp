package com.example.jadp.controller;

import com.example.jadp.dto.UpstagePiiOacResponse;
import com.example.jadp.service.UpstagePiiOacCompatibilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UpstagePiiOacController.class)
@Import(GlobalExceptionHandler.class)
class UpstagePiiOacControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    UpstagePiiOacCompatibilityService compatibilityService;

    @Test
    void detectReturnsOacPayload() throws Exception {
        when(compatibilityService.detect(any(), eq(true))).thenReturn(sampleResponse(null));

        MockMultipartFile document = new MockMultipartFile(
                "document",
                "sample.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/v1/pii-masker/oac/detect")
                        .file(document)
                        .param("wrap_image_as_pdf", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("oac"))
                .andExpect(jsonPath("$.items[0].key").value("mobile_phone_number"))
                .andExpect(jsonPath("$.items[0].boundingBoxes[0].vertices[0].x").value(10.0));
    }

    @Test
    void maskReturnsMaskedFileLink() throws Exception {
        when(compatibilityService.mask(any(), eq(true))).thenReturn(sampleResponse(
                new UpstagePiiOacResponse.MaskedDocument(
                        "artifact-1",
                        "masked.pdf",
                        MediaType.APPLICATION_PDF_VALUE,
                        "http://localhost/api/v1/pii/files/artifact-1"
                )));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/api/v1/pii/oac/mask")
                        .file(file)
                        .param("wrap_image_as_pdf", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maskedDocument.fileId").value("artifact-1"))
                .andExpect(jsonPath("$.maskedDocument.downloadUrl").value("http://localhost/api/v1/pii/files/artifact-1"));
    }

    private UpstagePiiOacResponse sampleResponse(UpstagePiiOacResponse.MaskedDocument maskedDocument) {
        return new UpstagePiiOacResponse(
                "1.0",
                "oac",
                "jadp-pii-masker-oac",
                new UpstagePiiOacResponse.Metadata(
                        "doc-1",
                        "sample.pdf",
                        MediaType.APPLICATION_PDF_VALUE,
                        "pdf",
                        1,
                        List.of(new UpstagePiiOacResponse.Page(1, 595, 842))
                ),
                List.of(new UpstagePiiOacResponse.Item(
                        "mobile_phone_number",
                        "MOBILE_PHONE_NUMBER",
                        "휴대폰번호",
                        "010-1234-5678",
                        "010-1234-****",
                        "pdf-structured",
                        List.of(new UpstagePiiOacResponse.BoundingBox(
                                1,
                                List.of(
                                        new UpstagePiiOacResponse.Vertex(10, 20),
                                        new UpstagePiiOacResponse.Vertex(110, 20),
                                        new UpstagePiiOacResponse.Vertex(110, 40),
                                        new UpstagePiiOacResponse.Vertex(10, 40)
                                )
                        ))
                )),
                maskedDocument
        );
    }
}

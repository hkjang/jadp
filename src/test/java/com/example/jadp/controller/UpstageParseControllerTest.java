package com.example.jadp.controller;

import com.example.jadp.dto.UpstageParseContent;
import com.example.jadp.dto.UpstageParseCoordinate;
import com.example.jadp.dto.UpstageParseElement;
import com.example.jadp.dto.UpstageParseResponse;
import com.example.jadp.dto.UpstageParseUsage;
import com.example.jadp.service.UpstageParseCompatibilityService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UpstageParseController.class)
@Import(GlobalExceptionHandler.class)
class UpstageParseControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    UpstageParseCompatibilityService compatibilityService;

    @Test
    void documentDigitizationReturnsCompatibilityPayload() throws Exception {
        when(compatibilityService.parse(any(), any())).thenReturn(new UpstageParseResponse(
                "2.0",
                new UpstageParseContent("<h1 id='0'>안내문</h1>", "# 안내문", "안내문"),
                List.of(new UpstageParseElement(
                        "heading1",
                        new UpstageParseContent("<h1 id='0'>안내문</h1>", "# 안내문", "안내문"),
                        List.of(
                                new UpstageParseCoordinate(0.1, 0.2),
                                new UpstageParseCoordinate(0.3, 0.2),
                                new UpstageParseCoordinate(0.3, 0.4),
                                new UpstageParseCoordinate(0.1, 0.4)
                        ),
                        0,
                        1
                )),
                "document-parse",
                new UpstageParseUsage(1)
        ));

        MockMultipartFile document = new MockMultipartFile(
                "document",
                "sample.png",
                MediaType.IMAGE_PNG_VALUE,
                new byte[]{1, 2, 3}
        );

        mockMvc.perform(multipart("/v1/document-digitization")
                        .file(document)
                        .param("ocr", "force")
                        .param("model", "document-parse")
                        .param("base64_encoding", "[\"table\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.api").value("2.0"))
                .andExpect(jsonPath("$.content.html").value("<h1 id='0'>안내문</h1>"))
                .andExpect(jsonPath("$.elements[0].category").value("heading1"))
                .andExpect(jsonPath("$.elements[0].coordinates[0].x").value(0.1))
                .andExpect(jsonPath("$.usage.pages").value(1));
    }
}

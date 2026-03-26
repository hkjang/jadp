package com.example.jadp.controller;

import com.example.jadp.config.HybridProcessingProperties;
import com.example.jadp.dto.OptionsResponse;
import com.example.jadp.model.ConversionJob;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.JobStatus;
import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.service.HybridOptionsResolver;
import com.example.jadp.service.PdfJobService;
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

@WebMvcTest(PdfController.class)
@Import(GlobalExceptionHandler.class)
class PdfControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    PdfJobService pdfJobService;

    @MockBean
    HybridOptionsResolver hybridOptionsResolver;

    @TempDir
    Path tempDir;

    @Test
    void optionsEndpointExposesSupportedValues() throws Exception {
        when(hybridOptionsResolver.properties()).thenReturn(new HybridProcessingProperties());
        mockMvc.perform(get("/api/v1/pdf/config/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formats[0]").value("json"))
                .andExpect(jsonPath("$.defaults.readingOrder").value("xycut"))
                .andExpect(jsonPath("$.defaults.hybrid").value("off"));
    }

    @Test
    void optionsEndpointReflectsHybridDefaultsWhenEnabled() throws Exception {
        HybridProcessingProperties properties = new HybridProcessingProperties();
        properties.setEnabled(true);
        properties.setAutoApplyToRequests(true);
        properties.setPreferFullMode(true);
        properties.setTimeoutMillis(120_000L);
        properties.setFallback(true);
        when(hybridOptionsResolver.properties()).thenReturn(properties);

        mockMvc.perform(get("/api/v1/pdf/config/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults.hybrid").value("docling-fast"))
                .andExpect(jsonPath("$.defaults.hybridMode").value("full"))
                .andExpect(jsonPath("$.defaults.hybridTimeout").value(120000))
                .andExpect(jsonPath("$.defaults.hybridFallback").value(true));
    }

    @Test
    void convertSyncReturnsPreviewUrlsAndArtifacts() throws Exception {
        Path markdown = Files.writeString(tempDir.resolve("sample.md"), "# hello");
        Path json = Files.writeString(tempDir.resolve("sample.json"), "{\"ok\":true}");
        Path html = Files.writeString(tempDir.resolve("sample.html"), "<h1>hello</h1>");

        PdfConversionOptions options = new PdfConversionOptions(
                List.of("markdown", "json", "html"),
                null,
                null,
                false,
                false,
                "xycut",
                "default",
                "external",
                "png",
                null,
                false,
                true,
                "off",
                "auto",
                null,
                30_000L,
                true
        );

        ConversionJob job = new ConversionJob(
                UUID.randomUUID(),
                "sample.pdf",
                128L,
                tempDir.resolve("input.pdf"),
                tempDir,
                options
        );
        job.markRunning();
        job.markSucceeded(List.of(
                new GeneratedArtifact("f1", "markdown", "sample.md", "text/markdown", Files.size(markdown), "sample.md", markdown),
                new GeneratedArtifact("f2", "json", "sample.json", "application/json", Files.size(json), "sample.json", json),
                new GeneratedArtifact("f3", "html", "sample.html", "text/html", Files.size(html), "sample.html", html)
        ));

        when(pdfJobService.convertSync(any(), any())).thenReturn(job);

        MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", MediaType.APPLICATION_PDF_VALUE, "pdf".getBytes());

        mockMvc.perform(multipart("/api/v1/pdf/convert-sync")
                        .file(file)
                        .param("formats", "markdown,json,html"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.getId().toString()))
                .andExpect(jsonPath("$.status").value(JobStatus.SUCCEEDED.name()))
                .andExpect(jsonPath("$.markdown").value("# hello"))
                .andExpect(jsonPath("$.jsonSummary").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.htmlPreviewUrl").exists())
                .andExpect(jsonPath("$.outputFiles.length()").value(3));
    }

    @Test
    void downloadFilePreservesUtf8ContentTypeForMarkdown() throws Exception {
        Path markdown = Files.writeString(tempDir.resolve("sample.md"), "# 한글");
        GeneratedArtifact artifact = new GeneratedArtifact(
                "markdown-1",
                "markdown",
                "sample.md",
                "text/markdown;charset=UTF-8",
                Files.size(markdown),
                "sample.md",
                markdown
        );

        when(pdfJobService.getArtifact("markdown-1")).thenReturn(artifact);

        mockMvc.perform(get("/api/v1/pdf/files/markdown-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown;charset=UTF-8"));
    }
}

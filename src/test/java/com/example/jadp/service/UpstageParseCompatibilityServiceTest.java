package com.example.jadp.service;

import com.example.jadp.dto.UpstageParseRequest;
import com.example.jadp.dto.UpstageParseResponse;
import com.example.jadp.model.ConversionJob;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.PdfConversionOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpstageParseCompatibilityServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void parseBuildsUpstageCompatibleResponseFromGeneratedArtifacts() throws Exception {
        PdfJobService pdfJobService = mock(PdfJobService.class);
        HybridDoclingParseService hybridDoclingParseService = mock(HybridDoclingParseService.class);
        when(hybridDoclingParseService.isConfigured()).thenReturn(false);
        UpstageParseCompatibilityService service = new UpstageParseCompatibilityService(
                pdfJobService,
                new ObjectMapper(),
                hybridDoclingParseService
        );

        Path uploadPdf = createPdf(tempDir.resolve("wrapped.pdf"), 200, 400);
        Path outputDir = Files.createDirectories(tempDir.resolve("output"));
        Path html = Files.writeString(outputDir.resolve("sample.html"), "<h1>안내문</h1>");
        Path markdown = Files.writeString(outputDir.resolve("sample.md"), "# 안내문");
        Path text = Files.writeString(outputDir.resolve("sample.txt"), "안내문");
        Path json = Files.writeString(outputDir.resolve("sample.json"), """
                {
                  "number of pages": 1,
                  "kids": [
                    {
                      "type": "heading",
                      "page number": 1,
                      "bounding box": [10, 300, 110, 350],
                      "content": "안내문"
                    },
                    {
                      "type": "paragraph",
                      "page number": 1,
                      "bounding box": [20, 120, 180, 260],
                      "content": "본문입니다"
                    }
                  ]
                }
                """);

        ConversionJob job = new ConversionJob(
                UUID.randomUUID(),
                "sample.pdf",
                128L,
                uploadPdf,
                outputDir,
                new PdfConversionOptions(
                        List.of("json", "html", "markdown", "text"),
                        null,
                        null,
                        false,
                        false,
                        "xycut",
                        "default",
                        "off",
                        "png",
                        null,
                        false,
                        false,
                        "off",
                        "auto",
                        null,
                        30_000L,
                        true
                )
        );
        job.markRunning();
        job.markSucceeded(List.of(
                artifact("json", json),
                artifact("html", html),
                artifact("markdown", markdown),
                artifact("text", text)
        ));

        when(pdfJobService.convertSync(any(), any())).thenReturn(job);

        MockMultipartFile document = new MockMultipartFile(
                "document",
                "sample.pdf",
                "application/pdf",
                "dummy".getBytes()
        );

        UpstageParseResponse response = service.parse(document, new UpstageParseRequest(
                "document-parse",
                "force",
                "[\"table\"]",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(response.api()).isEqualTo("2.0");
        assertThat(response.content().html()).isEqualTo("<h1>안내문</h1>");
        assertThat(response.content().markdown()).isEqualTo("# 안내문");
        assertThat(response.content().text()).isEqualTo("안내문");
        assertThat(response.model()).isEqualTo("document-parse");
        assertThat(response.usage().pages()).isEqualTo(1);
        assertThat(response.elements()).hasSize(2);
        assertThat(response.elements().get(0).category()).isEqualTo("heading1");
        assertThat(response.elements().get(0).coordinates().get(0).x()).isEqualTo(0.05);
        assertThat(response.elements().get(0).coordinates().get(0).y()).isEqualTo(0.125);
        assertThat(response.elements().get(1).content().text()).isEqualTo("본문입니다");

        verify(pdfJobService).convertSync(any(), argThat(options ->
                options.formats().containsAll(List.of("json", "html", "markdown", "text"))
                        && Boolean.FALSE.equals(options.sanitize())
                        && "off".equals(options.imageOutput())
        ));
    }

    @Test
    void parsePrefersHybridFallbackWhenStandardResponseIsImageOnly() throws Exception {
        PdfJobService pdfJobService = mock(PdfJobService.class);
        HybridDoclingParseService hybridDoclingParseService = mock(HybridDoclingParseService.class);
        when(hybridDoclingParseService.isConfigured()).thenReturn(true);
        UpstageParseCompatibilityService service = new UpstageParseCompatibilityService(
                pdfJobService,
                new ObjectMapper(),
                hybridDoclingParseService
        );

        Path uploadPdf = createPdf(tempDir.resolve("wrapped-image.pdf"), 2816, 1536);
        Path outputDir = Files.createDirectories(tempDir.resolve("compat-output"));
        Path html = Files.writeString(outputDir.resolve("sample.html"), "<html><body><img src='figure.png'></body></html>");
        Path markdown = Files.writeString(outputDir.resolve("sample.md"), "");
        Path text = Files.writeString(outputDir.resolve("sample.txt"), "");
        Path json = Files.writeString(outputDir.resolve("sample.json"), """
                {
                  "number of pages": 1,
                  "kids": [
                    {
                      "type": "image",
                      "page number": 1,
                      "bounding box": [0, 0, 2816, 1536]
                    }
                  ]
                }
                """);

        ConversionJob job = new ConversionJob(
                UUID.randomUUID(),
                "sample.pdf",
                128L,
                uploadPdf,
                outputDir,
                new PdfConversionOptions(
                        List.of("json", "html", "markdown", "text"),
                        null,
                        null,
                        false,
                        false,
                        "xycut",
                        "default",
                        "off",
                        "png",
                        null,
                        false,
                        false,
                        "off",
                        "auto",
                        null,
                        30_000L,
                        true
                )
        );
        job.markRunning();
        job.markSucceeded(List.of(
                artifact("json", json),
                artifact("html", html),
                artifact("markdown", markdown),
                artifact("text", text)
        ));
        when(pdfJobService.convertSync(any(), any())).thenReturn(job);
        when(hybridDoclingParseService.parse(eq(uploadPdf), eq("application/pdf"), eq("document-parse"), eq(true)))
                .thenReturn(new UpstageParseResponse(
                        "2.0",
                        new com.example.jadp.dto.UpstageParseContent("<html><body><p>개인정보 보호</p></body></html>", "개인정보 보호", "개인정보 보호"),
                        List.of(new com.example.jadp.dto.UpstageParseElement(
                                "paragraph",
                                new com.example.jadp.dto.UpstageParseContent("<p>개인정보 보호</p>", "개인정보 보호", "개인정보 보호"),
                                List.of(
                                        new com.example.jadp.dto.UpstageParseCoordinate(0.1, 0.1),
                                        new com.example.jadp.dto.UpstageParseCoordinate(0.9, 0.1),
                                        new com.example.jadp.dto.UpstageParseCoordinate(0.9, 0.2),
                                        new com.example.jadp.dto.UpstageParseCoordinate(0.1, 0.2)
                                ),
                                0,
                                1
                        )),
                        "document-parse",
                        new com.example.jadp.dto.UpstageParseUsage(1)
                ));

        MockMultipartFile document = new MockMultipartFile(
                "document",
                "sample.pdf",
                "application/pdf",
                "dummy".getBytes()
        );

        UpstageParseResponse response = service.parse(document, new UpstageParseRequest(
                "document-parse",
                "force",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));

        assertThat(response.content().text()).isEqualTo("개인정보 보호");
        assertThat(response.elements()).hasSize(1);
        assertThat(response.elements().get(0).category()).isEqualTo("paragraph");
    }

    private GeneratedArtifact artifact(String format, Path file) throws IOException {
        return new GeneratedArtifact(
                UUID.randomUUID().toString(),
                format,
                file.getFileName().toString(),
                "application/octet-stream",
                Files.size(file),
                file.getFileName().toString(),
                file
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

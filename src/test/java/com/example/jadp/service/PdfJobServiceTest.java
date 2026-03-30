package com.example.jadp.service;

import com.example.jadp.config.StorageProperties;
import com.example.jadp.model.ConversionJob;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.JobStatus;
import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.config.HybridProcessingProperties;
import com.example.jadp.support.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfJobServiceTest {

    @TempDir
    Path tempDir;

    private HybridOptionsResolver hybridOptionsResolver() {
        return new HybridOptionsResolver(new HybridProcessingProperties());
    }

    @Test
    void convertSyncCreatesArtifactsAndSanitizesTextOutputs() throws IOException {
        StorageProperties properties = new StorageProperties();
        properties.setBaseDir(tempDir.toString());

        PdfConversionEngine engine = new StubEngine() {
            @Override
            public void convert(Path inputFile, Path outputDirectory, PdfConversionOptions options) {
                try {
                    Files.writeString(outputDirectory.resolve("result.md"), "Contact me at qa@example.com");
                    Files.writeString(outputDirectory.resolve("result.json"), "{\"email\":\"qa@example.com\"}");
                    Files.writeString(outputDirectory.resolve("result.html"), "<p>Visit https://example.com</p>");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        PdfJobService service = new PdfJobService(
                engine,
                hybridOptionsResolver(),
                new SensitiveDataSanitizer(),
                Runnable::run,
                properties
        );

        MockMultipartFile file = new MockMultipartFile(
                "file", "sample.pdf", "application/pdf", "dummy".getBytes()
        );

        ConversionJob job = service.convertSync(file, new PdfConversionOptions(
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
        ));

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getArtifacts()).hasSize(3);
        GeneratedArtifact markdownArtifact = job.getArtifacts().stream()
                .filter(artifact -> "markdown".equals(artifact.format()))
                .findFirst()
                .orElseThrow();
        assertThat(markdownArtifact.contentType()).isEqualTo("text/markdown;charset=UTF-8");
        assertThat(Files.readString(job.getArtifacts().get(0).absolutePath())
                + Files.readString(job.getArtifacts().get(1).absolutePath())
                + Files.readString(job.getArtifacts().get(2).absolutePath()))
                .doesNotContain("qa@example.com")
                .doesNotContain("https://example.com")
                .contains("[EMAIL]")
                .contains("[URL]");
    }

    @Test
    void submitAsyncMarksFailedWhenEngineThrows() {
        StorageProperties properties = new StorageProperties();
        properties.setBaseDir(tempDir.toString());

        PdfConversionEngine engine = new StubEngine() {
            @Override
            public void convert(Path inputFile, Path outputDirectory, PdfConversionOptions options) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "simulated failure");
            }
        };

        PdfJobService service = new PdfJobService(
                engine,
                hybridOptionsResolver(),
                new SensitiveDataSanitizer(),
                Runnable::run,
                properties
        );

        MockMultipartFile file = new MockMultipartFile(
                "file", "broken.pdf", "application/pdf", "dummy".getBytes()
        );

        ConversionJob job = service.submitAsync(file, new PdfConversionOptions(
                List.of("json"),
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
        ));

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getError()).contains("simulated failure");
    }

    @Test
    void convertSyncRewritesMarkdownImagePathsToApiDownloads() throws IOException {
        StorageProperties properties = new StorageProperties();
        properties.setBaseDir(tempDir.toString());

        PdfConversionEngine engine = new StubEngine() {
            @Override
            public void convert(Path inputFile, Path outputDirectory, PdfConversionOptions options) {
                try {
                    Path imageDir = outputDirectory.resolve("result_images");
                    Files.createDirectories(imageDir);
                    Path imageFile = Files.write(imageDir.resolve("figure1.png"), new byte[]{1, 2, 3});
                    Files.writeString(outputDirectory.resolve("result.md"),
                            "![figure](" + imageFile.toString() + ")");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        PdfJobService service = new PdfJobService(
                engine,
                hybridOptionsResolver(),
                new SensitiveDataSanitizer(),
                Runnable::run,
                properties
        );

        MockMultipartFile file = new MockMultipartFile(
                "file", "images.pdf", "application/pdf", "dummy".getBytes()
        );

        ConversionJob job = service.convertSync(file, new PdfConversionOptions(
                List.of("markdown-with-images"),
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
                false,
                "off",
                "auto",
                null,
                30_000L,
                true
        ));

        GeneratedArtifact markdown = job.getArtifacts().stream()
                .filter(artifact -> "markdown".equals(artifact.format()))
                .findFirst()
                .orElseThrow();
        GeneratedArtifact image = job.getArtifacts().stream()
                .filter(artifact -> "image".equals(artifact.format()))
                .findFirst()
                .orElseThrow();

        String markdownContent = Files.readString(markdown.absolutePath());
        assertThat(markdownContent)
                .contains("/api/v1/pdf/files/" + image.id())
                .doesNotContain(image.absolutePath().toString());
    }

    @Test
    void convertSyncWrapsPngUploadIntoSinglePagePdfBeforeConversion() throws IOException {
        StorageProperties properties = new StorageProperties();
        properties.setBaseDir(tempDir.toString());

        PdfConversionEngine engine = new StubEngine() {
            @Override
            public void convert(Path inputFile, Path outputDirectory, PdfConversionOptions options) {
                assertThat(inputFile.getFileName().toString()).endsWith(".pdf");
                try (PDDocument document = Loader.loadPDF(inputFile.toFile())) {
                    assertThat(document.getNumberOfPages()).isEqualTo(1);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
                try {
                    Files.writeString(outputDirectory.resolve("result.json"), "{\"ok\":true}");
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        PdfJobService service = new PdfJobService(
                engine,
                hybridOptionsResolver(),
                new SensitiveDataSanitizer(),
                Runnable::run,
                properties
        );

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "page.png",
                "image/png",
                createPngBytes(32, 48)
        );

        ConversionJob job = service.convertSync(file, new PdfConversionOptions(
                List.of("json"),
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
        ));

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getUploadPath().getFileName().toString()).endsWith(".pdf");
    }

    private byte[] createPngBytes(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
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

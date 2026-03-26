package com.example.jadp.controller;

import com.example.jadp.config.HybridProcessingProperties;
import com.example.jadp.dto.ConversionAcceptedResponse;
import com.example.jadp.dto.FileItemResponse;
import com.example.jadp.dto.JobDetailResponse;
import com.example.jadp.dto.JobListResponse;
import com.example.jadp.dto.OptionsResponse;
import com.example.jadp.dto.PdfConvertRequest;
import com.example.jadp.dto.SyncConvertResponse;
import com.example.jadp.model.ConversionJob;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.JobStatus;
import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.service.PdfJobService;
import com.example.jadp.service.HybridOptionsResolver;
import com.example.jadp.service.HybridUsage;
import com.example.jadp.support.ApiException;
import com.example.jadp.support.OptionCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pdf")
@Tag(name = "PDF", description = "OpenDataLoader PDF conversion endpoints")
public class PdfController {

    private final PdfJobService pdfJobService;
    private final HybridOptionsResolver hybridOptionsResolver;

    public PdfController(PdfJobService pdfJobService, HybridOptionsResolver hybridOptionsResolver) {
        this.pdfJobService = pdfJobService;
        this.hybridOptionsResolver = hybridOptionsResolver;
    }

    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Create an asynchronous PDF conversion job",
            responses = {
                    @ApiResponse(responseCode = "202", description = "Job accepted"),
                    @ApiResponse(responseCode = "400", description = "Invalid options"),
                    @ApiResponse(responseCode = "413", description = "File too large"),
                    @ApiResponse(responseCode = "415", description = "Unsupported file type"),
                    @ApiResponse(responseCode = "503", description = "OpenDataLoader unavailable")
            }
    )
    public ResponseEntity<ConversionAcceptedResponse> convert(@Valid @ModelAttribute PdfConvertRequest request) {
        ConversionJob job = pdfJobService.submitAsync(request.getFile(), request.toOptions());
        ConversionAcceptedResponse response = new ConversionAcceptedResponse(
                job.getId().toString(),
                job.getStatus().name(),
                "Job accepted. Poll /api/v1/pdf/jobs/{jobId} for completion.",
                List.of()
        );
        return ResponseEntity.accepted().body(response);
    }

    @PostMapping(value = "/convert-sync", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Convert a small PDF synchronously",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Conversion completed"),
                    @ApiResponse(responseCode = "422", description = "Conversion failed",
                            content = @Content(schema = @Schema(implementation = String.class),
                                    examples = @ExampleObject(value = "{\"message\":\"OpenDataLoader PDF conversion failed\"}")))
            }
    )
    public SyncConvertResponse convertSync(@Valid @ModelAttribute PdfConvertRequest request) {
        ConversionJob job = pdfJobService.convertSync(request.getFile(), request.toOptions());
        if (job.getStatus() == JobStatus.FAILED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, job.getError());
        }
        List<FileItemResponse> files = mapFiles(job.getArtifacts());
        return new SyncConvertResponse(
                job.getId().toString(),
                job.getStatus().name(),
                previewContent(findByFormat(job, "markdown"), 16_000),
                previewContent(findByFormat(job, "json"), 16_000),
                previewUrl(findByFormat(job, "html")),
                files,
                job.getError()
        );
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get a conversion job by ID")
    public JobDetailResponse getJob(@PathVariable UUID jobId) {
        return toJobResponse(pdfJobService.getJob(jobId));
    }

    @GetMapping("/jobs")
    @Operation(summary = "List recent conversion jobs")
    public JobListResponse listJobs(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return new JobListResponse(
                page,
                size,
                pdfJobService.listJobs(page, size).stream().map(this::toJobResponse).toList()
        );
    }

    @GetMapping("/files/{fileId}")
    @Operation(summary = "Download or preview a generated file")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) throws IOException {
        GeneratedArtifact artifact = pdfJobService.getArtifact(fileId);
        Resource resource = new UrlResource(artifact.absolutePath().toUri());
        if (!resource.exists()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Generated file not found on disk.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(artifact.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentLength(artifact.size())
                .body(resource);
    }

    @GetMapping("/config/options")
    @Operation(summary = "Get supported option values for the UI")
    public OptionsResponse options() {
        HybridProcessingProperties hybridProperties = Optional.ofNullable(hybridOptionsResolver.properties())
                .orElseGet(HybridProcessingProperties::new);
        PdfConversionOptions defaultOptions = new HybridOptionsResolver(hybridProperties).applyDefaults(
                new PdfConversionOptions(
                        List.of("json"),
                        null,
                        null,
                        false,
                        false,
                        OptionCatalog.DEFAULT_READING_ORDER,
                        OptionCatalog.DEFAULT_TABLE_METHOD,
                        OptionCatalog.DEFAULT_IMAGE_OUTPUT,
                        OptionCatalog.DEFAULT_IMAGE_FORMAT,
                        null,
                        false,
                        true,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                HybridUsage.PDF_REQUEST
        );
        return new OptionsResponse(
                OptionCatalog.FORMATS,
                OptionCatalog.READING_ORDERS,
                OptionCatalog.TABLE_METHODS,
                OptionCatalog.IMAGE_OUTPUTS,
                OptionCatalog.IMAGE_FORMATS,
                OptionCatalog.HYBRID_BACKENDS,
                OptionCatalog.HYBRID_MODES,
                new OptionsResponse.Defaults(
                        OptionCatalog.DEFAULT_READING_ORDER,
                        OptionCatalog.DEFAULT_TABLE_METHOD,
                        OptionCatalog.DEFAULT_IMAGE_OUTPUT,
                        OptionCatalog.DEFAULT_IMAGE_FORMAT,
                        defaultOptions.hybrid(),
                        defaultOptions.hybridMode(),
                        defaultOptions.hybridTimeout() == null ? 30_000L : defaultOptions.hybridTimeout(),
                        true,
                        false,
                        Boolean.TRUE.equals(defaultOptions.hybridFallback())
                )
        );
    }

    private JobDetailResponse toJobResponse(ConversionJob job) {
        return new JobDetailResponse(
                job.getId().toString(),
                job.getStatus().name(),
                job.getSourceFilename(),
                job.getSourceSize(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getEndedAt(),
                job.getProcessingMillis(),
                job.getError(),
                job.getOptions().formats(),
                mapFiles(job.getArtifacts())
        );
    }

    private List<FileItemResponse> mapFiles(List<GeneratedArtifact> artifacts) {
        return artifacts.stream()
                .map(artifact -> new FileItemResponse(
                        artifact.id(),
                        artifact.format(),
                        artifact.filename(),
                        artifact.contentType(),
                        artifact.size(),
                        artifact.relativePath(),
                        fileUrl(artifact.id())
                ))
                .toList();
    }

    private GeneratedArtifact findByFormat(ConversionJob job, String format) {
        return job.getArtifacts().stream()
                .filter(file -> format.equals(file.format()))
                .findFirst()
                .orElse(null);
    }

    private String previewContent(GeneratedArtifact artifact, int maxChars) {
        if (artifact == null) {
            return null;
        }
        try {
            String raw = Files.readString(artifact.absolutePath());
            if (raw.length() <= maxChars) {
                return raw;
            }
            return raw.substring(0, maxChars) + "\n\n... (truncated)";
        } catch (IOException ex) {
            return "Preview unavailable: " + ex.getMessage();
        }
    }

    private String previewUrl(GeneratedArtifact artifact) {
        return artifact == null ? null : fileUrl(artifact.id());
    }

    private String fileUrl(String fileId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/pdf/files/{fileId}")
                .buildAndExpand(fileId)
                .toUriString();
    }
}

package com.example.jadp.controller;

import com.example.jadp.dto.PiiBoundingBoxResponse;
import com.example.jadp.dto.PiiDetectResponse;
import com.example.jadp.dto.PiiFindingResponse;
import com.example.jadp.dto.PiiMaskResponse;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiMaskingResult;
import com.example.jadp.service.PiiDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/pii")
@Tag(name = "PII", description = "PII detection and masking endpoints")
public class PiiController {

    private final PiiDocumentService piiDocumentService;

    public PiiController(PiiDocumentService piiDocumentService) {
        this.piiDocumentService = piiDocumentService;
    }

    @PostMapping(value = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Detect PII and return bounding boxes",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Detection completed"),
                    @ApiResponse(responseCode = "503", description = "PNG OCR backend not configured")
            }
    )
    public PiiDetectResponse detect(@RequestPart("file") MultipartFile file) {
        return toDetectResponse(piiDocumentService.detect(file));
    }

    @PostMapping(value = "/mask", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Detect PII and produce a downloadable masked file")
    public PiiMaskResponse mask(@RequestPart("file") MultipartFile file) {
        PiiMaskingResult result = piiDocumentService.mask(file);
        GeneratedArtifact artifact = result.maskedArtifact();
        return new PiiMaskResponse(
                result.detectionResult().documentId().toString(),
                result.detectionResult().originalFilename(),
                artifact.id(),
                artifact.filename(),
                artifact.contentType(),
                fileUrl(artifact.id()),
                result.detectionResult().findings().size(),
                mapFindings(result.detectionResult().findings())
        );
    }

    @GetMapping("/files/{fileId}")
    @Operation(summary = "Download a masked PII file")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileId) throws IOException {
        GeneratedArtifact artifact = piiDocumentService.getArtifact(fileId);
        Resource resource = new UrlResource(artifact.absolutePath().toUri());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(artifact.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentLength(artifact.size())
                .body(resource);
    }

    private PiiDetectResponse toDetectResponse(PiiDetectionResult result) {
        return new PiiDetectResponse(
                result.documentId().toString(),
                result.originalFilename(),
                result.contentType(),
                result.mediaType(),
                result.pageCount(),
                result.findings().size(),
                mapFindings(result.findings())
        );
    }

    private List<PiiFindingResponse> mapFindings(List<PiiFinding> findings) {
        return findings.stream()
                .map(finding -> new PiiFindingResponse(
                        finding.type().name(),
                        finding.label(),
                        finding.originalText(),
                        finding.maskedText(),
                        finding.pageNumber(),
                        new PiiBoundingBoxResponse(
                                finding.boundingBox().x(),
                                finding.boundingBox().y(),
                                finding.boundingBox().width(),
                                finding.boundingBox().height()
                        ),
                        finding.detectionSource()
                ))
                .toList();
    }

    private String fileUrl(String fileId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/pii/files/{fileId}")
                .buildAndExpand(fileId)
                .toUriString();
    }
}

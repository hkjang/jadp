package com.example.jadp.controller;

import com.example.jadp.dto.UpstagePiiOacResponse;
import com.example.jadp.service.UpstagePiiOacCompatibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping
@Tag(name = "PII OAC", description = "Upstage PII Masker OAC-compatible endpoints")
public class UpstagePiiOacController {

    private final UpstagePiiOacCompatibilityService compatibilityService;

    public UpstagePiiOacController(UpstagePiiOacCompatibilityService compatibilityService) {
        this.compatibilityService = compatibilityService;
    }

    @PostMapping(
            value = {"/v1/pii-masker/oac/detect", "/api/v1/pii/oac/detect"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(
            summary = "Detect PII using Upstage OAC-compatible response structure",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Detection completed",
                            content = @Content(schema = @Schema(implementation = UpstagePiiOacResponse.class)))
            }
    )
    public UpstagePiiOacResponse detect(
            @RequestPart(value = "document", required = false) MultipartFile document,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "wrap_image_as_pdf", defaultValue = "true") boolean wrapImageAsPdf) {
        return compatibilityService.detect(resolveFile(document, file), wrapImageAsPdf);
    }

    @PostMapping(
            value = {"/v1/pii-masker/oac/mask", "/api/v1/pii/oac/mask"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(summary = "Mask PII and return OAC-compatible response with masked document link")
    public UpstagePiiOacResponse mask(
            @RequestPart(value = "document", required = false) MultipartFile document,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "wrap_image_as_pdf", defaultValue = "true") boolean wrapImageAsPdf) {
        return compatibilityService.mask(resolveFile(document, file), wrapImageAsPdf);
    }

    private MultipartFile resolveFile(MultipartFile document, MultipartFile file) {
        if (document != null && !document.isEmpty()) {
            return document;
        }
        if (file != null && !file.isEmpty()) {
            return file;
        }
        return null;
    }
}

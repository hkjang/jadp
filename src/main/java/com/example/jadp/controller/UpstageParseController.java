package com.example.jadp.controller;

import com.example.jadp.dto.UpstageParseRequest;
import com.example.jadp.dto.UpstageParseResponse;
import com.example.jadp.service.UpstageParseCompatibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Parse Compatibility", description = "Upstage Parse-compatible wrapper over the OpenDataLoader pipeline")
public class UpstageParseController {

    private final UpstageParseCompatibilityService compatibilityService;

    public UpstageParseController(UpstageParseCompatibilityService compatibilityService) {
        this.compatibilityService = compatibilityService;
    }

    @PostMapping(
            value = {"/v1/document-digitization", "/api/v1/pdf/document-digitization"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @Operation(
            summary = "Parse a document using an Upstage-compatible request and response shape",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Parsed successfully",
                            content = @Content(schema = @Schema(implementation = UpstageParseResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Missing or invalid request"),
                    @ApiResponse(responseCode = "415", description = "Unsupported file type"),
                    @ApiResponse(responseCode = "422", description = "Conversion failed")
            }
    )
    public UpstageParseResponse documentDigitization(
            @RequestParam(name = "document", required = false) MultipartFile document,
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "ocr", required = false) String ocr,
            @RequestParam(name = "base64_encoding", required = false) String base64Encoding,
            @RequestParam(name = "password", required = false) String password,
            @RequestParam(name = "pages", required = false) String pages,
            @RequestParam(name = "keep_line_breaks", required = false) Boolean keepLineBreaks,
            @RequestParam(name = "use_struct_tree", required = false) Boolean useStructTree,
            @RequestParam(name = "reading_order", required = false) String readingOrder,
            @RequestParam(name = "table_method", required = false) String tableMethod,
            @RequestParam(name = "image_output", required = false) String imageOutput,
            @RequestParam(name = "image_format", required = false) String imageFormat,
            @RequestParam(name = "include_header_footer", required = false) Boolean includeHeaderFooter,
            @RequestParam(name = "hybrid", required = false) String hybrid,
            @RequestParam(name = "hybrid_mode", required = false) String hybridMode,
            @RequestParam(name = "hybrid_url", required = false) String hybridUrl,
            @RequestParam(name = "hybrid_timeout", required = false) Long hybridTimeout,
            @RequestParam(name = "hybrid_fallback", required = false) Boolean hybridFallback) {
        MultipartFile source = document != null && !document.isEmpty() ? document : file;
        UpstageParseRequest request = new UpstageParseRequest(
                model,
                ocr,
                base64Encoding,
                password,
                pages,
                keepLineBreaks,
                useStructTree,
                readingOrder,
                tableMethod,
                imageOutput,
                imageFormat,
                includeHeaderFooter,
                hybrid,
                hybridMode,
                hybridUrl,
                hybridTimeout,
                hybridFallback
        );
        return compatibilityService.parse(source, request);
    }
}

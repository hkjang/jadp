package com.example.jadp.model;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public record PiiDetectionResult(
        UUID documentId,
        String originalFilename,
        String contentType,
        String mediaType,
        int pageCount,
        Path sourceFile,
        List<PiiFinding> findings
) {
}

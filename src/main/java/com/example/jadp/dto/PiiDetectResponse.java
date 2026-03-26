package com.example.jadp.dto;

import java.util.List;

public record PiiDetectResponse(
        String documentId,
        String originalFilename,
        String contentType,
        String mediaType,
        int pageCount,
        int findingCount,
        List<PiiFindingResponse> findings
) {
}

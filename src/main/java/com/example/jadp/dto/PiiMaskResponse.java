package com.example.jadp.dto;

import java.util.List;

public record PiiMaskResponse(
        String documentId,
        String originalFilename,
        String maskedFileId,
        String maskedFilename,
        String maskedContentType,
        String maskedDownloadUrl,
        int findingCount,
        List<PiiFindingResponse> findings
) {
}

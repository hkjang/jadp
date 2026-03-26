package com.example.jadp.dto;

import java.time.Instant;
import java.util.List;

public record JobDetailResponse(
        String jobId,
        String status,
        String sourceFilename,
        long sourceSize,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt,
        Long processingMillis,
        String error,
        List<String> requestedFormats,
        List<FileItemResponse> files
) {
}


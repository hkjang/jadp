package com.example.jadp.dto;

import java.util.List;

public record SyncConvertResponse(
        String jobId,
        String status,
        String markdown,
        String jsonSummary,
        String htmlPreviewUrl,
        List<FileItemResponse> outputFiles,
        String error
) {
}


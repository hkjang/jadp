package com.example.jadp.dto;

import java.util.List;

public record ConversionAcceptedResponse(
        String jobId,
        String status,
        String message,
        List<FileItemResponse> outputFiles
) {
}


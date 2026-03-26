package com.example.jadp.dto;

public record FileItemResponse(
        String fileId,
        String format,
        String filename,
        String contentType,
        long size,
        String relativePath,
        String downloadUrl
) {
}


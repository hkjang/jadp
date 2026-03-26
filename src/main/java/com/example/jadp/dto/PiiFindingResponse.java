package com.example.jadp.dto;

public record PiiFindingResponse(
        String type,
        String label,
        String originalText,
        String maskedText,
        int pageNumber,
        PiiBoundingBoxResponse boundingBox,
        String detectionSource
) {
}

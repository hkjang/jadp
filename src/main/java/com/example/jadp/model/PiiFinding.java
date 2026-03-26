package com.example.jadp.model;

public record PiiFinding(
        PiiType type,
        String label,
        String originalText,
        String maskedText,
        int pageNumber,
        PiiBoundingBox boundingBox,
        String detectionSource
) {
}

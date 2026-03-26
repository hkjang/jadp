package com.example.jadp.model;

public record PiiMaskingResult(
        PiiDetectionResult detectionResult,
        GeneratedArtifact maskedArtifact
) {
}

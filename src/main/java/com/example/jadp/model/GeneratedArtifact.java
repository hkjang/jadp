package com.example.jadp.model;

import java.nio.file.Path;

public record GeneratedArtifact(
        String id,
        String format,
        String filename,
        String contentType,
        long size,
        String relativePath,
        Path absolutePath
) {
}


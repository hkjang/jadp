package com.example.jadp.dto;

import java.util.List;

public record UpstagePiiOacResponse(
        String api,
        String schema,
        String model,
        Metadata metadata,
        List<Item> items,
        MaskedDocument maskedDocument
) {

    public record Metadata(
            String documentId,
            String originalFilename,
            String contentType,
            String mediaType,
            int pageCount,
            List<Page> pages
    ) {
    }

    public record Page(
            int page,
            double width,
            double height
    ) {
    }

    public record Item(
            String key,
            String type,
            String label,
            String value,
            String maskedValue,
            String detectionSource,
            List<BoundingBox> boundingBoxes
    ) {
    }

    public record BoundingBox(
            int page,
            List<Vertex> vertices
    ) {
    }

    public record Vertex(
            double x,
            double y
    ) {
    }

    public record MaskedDocument(
            String fileId,
            String filename,
            String contentType,
            String downloadUrl
    ) {
    }
}

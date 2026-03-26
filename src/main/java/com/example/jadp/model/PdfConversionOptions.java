package com.example.jadp.model;

import java.util.List;

public record PdfConversionOptions(
        List<String> formats,
        String password,
        String pages,
        Boolean keepLineBreaks,
        Boolean useStructTree,
        String readingOrder,
        String tableMethod,
        String imageOutput,
        String imageFormat,
        String imageDir,
        Boolean includeHeaderFooter,
        Boolean sanitize,
        String hybrid,
        String hybridMode,
        String hybridUrl,
        Long hybridTimeout,
        Boolean hybridFallback
) {
}


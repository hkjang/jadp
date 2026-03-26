package com.example.jadp.dto;

import java.util.List;

public record OptionsResponse(
        List<String> formats,
        List<String> readingOrders,
        List<String> tableMethods,
        List<String> imageOutputs,
        List<String> imageFormats,
        List<String> hybridBackends,
        List<String> hybridModes,
        Defaults defaults
) {
    public record Defaults(
            String readingOrder,
            String tableMethod,
            String imageOutput,
            String imageFormat,
            String hybrid,
            String hybridMode,
            long hybridTimeout,
            boolean sanitize,
            boolean includeHeaderFooter,
            boolean hybridFallback
    ) {
    }
}


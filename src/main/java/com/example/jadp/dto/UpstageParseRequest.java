package com.example.jadp.dto;

import com.example.jadp.model.PdfConversionOptions;
import org.springframework.util.StringUtils;

import java.util.List;

public record UpstageParseRequest(
        String model,
        String ocr,
        String base64Encoding,
        String password,
        String pages,
        Boolean keepLineBreaks,
        Boolean useStructTree,
        String readingOrder,
        String tableMethod,
        String imageOutput,
        String imageFormat,
        Boolean includeHeaderFooter,
        String hybrid,
        String hybridMode,
        String hybridUrl,
        Long hybridTimeout,
        Boolean hybridFallback
) {

    public PdfConversionOptions toPdfOptions() {
        return new PdfConversionOptions(
                List.of("json", "html", "markdown", "text"),
                blankToNull(password),
                blankToNull(pages),
                defaultBoolean(keepLineBreaks, false),
                defaultBoolean(useStructTree, false),
                blankToDefault(readingOrder, "xycut"),
                blankToDefault(tableMethod, "default"),
                blankToDefault(imageOutput, "off"),
                blankToDefault(imageFormat, "png"),
                null,
                defaultBoolean(includeHeaderFooter, false),
                false,
                blankToDefault(hybrid, "off"),
                blankToDefault(hybridMode, "auto"),
                blankToNull(hybridUrl),
                hybridTimeout,
                defaultBoolean(hybridFallback, true)
        );
    }

    public String effectiveModel() {
        return blankToDefault(model, "document-parse");
    }

    private static String blankToDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}

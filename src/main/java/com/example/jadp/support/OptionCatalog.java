package com.example.jadp.support;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class OptionCatalog {

    public static final List<String> FORMATS = List.of(
            "json", "text", "html", "pdf", "markdown", "markdown-with-html", "markdown-with-images"
    );
    public static final List<String> READING_ORDERS = List.of("off", "xycut");
    public static final List<String> TABLE_METHODS = List.of("default", "cluster");
    public static final List<String> IMAGE_OUTPUTS = List.of("off", "embedded", "external");
    public static final List<String> IMAGE_FORMATS = List.of("png", "jpeg");
    public static final List<String> HYBRID_BACKENDS = List.of("off", "docling-fast");
    public static final List<String> HYBRID_MODES = List.of("auto", "full");
    public static final String DEFAULT_READING_ORDER = "xycut";
    public static final String DEFAULT_TABLE_METHOD = "default";
    public static final String DEFAULT_IMAGE_OUTPUT = "external";
    public static final String DEFAULT_IMAGE_FORMAT = "png";
    public static final String DEFAULT_HYBRID = "off";
    public static final String DEFAULT_HYBRID_MODE = "auto";

    private OptionCatalog() {
    }

    public static List<String> parseFormats(Collection<String> formats) {
        if (formats == null) {
            return List.of();
        }
        return formats.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(token -> token.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
    }

    public static String normalizeOptional(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : defaultValue;
    }

    public static void validateAllowedValues(String fieldName, Collection<String> values, Collection<String> allowed) {
        for (String value : values) {
            validateAllowedValue(fieldName, value, allowed);
        }
    }

    public static void validateAllowedValue(String fieldName, String value, Collection<String> allowed) {
        if (value == null) {
            return;
        }
        if (!allowed.contains(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unsupported " + fieldName + ": " + value + ". Allowed values: " + allowed);
        }
    }
}


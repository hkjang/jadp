package com.example.jadp.support;

public final class FileNameSanitizer {

    private FileNameSanitizer() {
    }

    /**
     * Sanitize a filename while preserving Unicode letters (Korean, CJK, etc.).
     * Allows: Unicode letters, digits, dot, hyphen, underscore, space.
     * Collapses consecutive underscores and trims leading/trailing underscores from the stem.
     */
    public static String sanitize(String filename) {
        // Preserve Unicode letters (\p{L}), digits (\p{N}), and safe punctuation
        String sanitized = filename.replaceAll("[^\\p{L}\\p{N}._ -]", "_");

        // Collapse consecutive underscores/spaces into a single underscore
        sanitized = sanitized.replaceAll("[_ ]{2,}", "_");

        // Split stem and extension
        String stem;
        String extension;
        int lastDot = sanitized.lastIndexOf('.');
        if (lastDot > 0) {
            stem = sanitized.substring(0, lastDot);
            extension = sanitized.substring(lastDot);
        } else {
            stem = sanitized;
            extension = "";
        }

        // Trim leading/trailing underscores from stem
        stem = stem.replaceAll("^_+|_+$", "");

        if (stem.isEmpty()) {
            stem = "document";
        }

        // Ensure .pdf extension
        if (!extension.equalsIgnoreCase(".pdf")
                && !extension.equalsIgnoreCase(".png")
                && !extension.equalsIgnoreCase(".jpg")
                && !extension.equalsIgnoreCase(".jpeg")) {
            extension = ".pdf";
        }

        return stem + extension;
    }
}

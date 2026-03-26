package com.example.jadp.support;

public final class FileNameSanitizer {

    private FileNameSanitizer() {
    }

    public static String sanitize(String filename) {
        String sanitized = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!sanitized.toLowerCase().endsWith(".pdf")) {
            sanitized = sanitized + ".pdf";
        }
        return sanitized;
    }
}


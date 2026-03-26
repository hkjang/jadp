package com.example.jadp.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SensitiveDataSanitizer {

    private static final List<String> TEXT_EXTENSIONS = List.of(".md", ".markdown", ".txt", ".json", ".html", ".htm");
    private static final List<SanitizeRule> RULES = List.of(
            new SanitizeRule(Pattern.compile("\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b"), "[EMAIL]"),
            new SanitizeRule(Pattern.compile("\\b(?:\\+?\\d{1,3}[ -]?)?(?:\\(?\\d{2,4}\\)?[ -]?)?\\d{3,4}[ -]?\\d{4}\\b"), "[PHONE]"),
            new SanitizeRule(Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "[IP]"),
            new SanitizeRule(Pattern.compile("\\b(?:https?://|www\\.)\\S+\\b"), "[URL]"),
            new SanitizeRule(Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b"), "[CARD]")
    );

    public void sanitizeRecursively(Path outputDirectory) throws IOException {
        if (!Files.exists(outputDirectory)) {
            return;
        }
        try (var stream = Files.walk(outputDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isTextLike)
                    .forEach(this::sanitizeFileUnchecked);
        }
    }

    private boolean isTextLike(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        return TEXT_EXTENSIONS.stream().anyMatch(filename::endsWith);
    }

    private void sanitizeFileUnchecked(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String sanitized = content;
            for (SanitizeRule rule : RULES) {
                sanitized = rule.pattern().matcher(sanitized).replaceAll(rule.replacement());
            }
            if (!sanitized.equals(content)) {
                Files.writeString(file, sanitized, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Skip files that are not UTF-8 text.
        }
    }

    private record SanitizeRule(Pattern pattern, String replacement) {
    }
}

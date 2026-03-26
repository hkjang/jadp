package com.example.jadp.service;

import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiType;
import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.support.PiiPatternMatcher;
import com.example.jadp.support.PiiTextMatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PdfStructuredPiiDetector {

    private static final String FIELD_LABEL_REGEX = "(?:성명|이름|대표자|주민등록번호|운전면허번호|여권번호|외국인등록번호|휴대폰번호|전화번호|신용카드번호|계좌번호|이메일|IP주소|주소|도로명주소)";
    private static final Pattern FIELD_CHUNK_PATTERN = Pattern.compile("(?<!\\S)(" + FIELD_LABEL_REGEX + "\\s*[:：]\\s*.*?)(?=(?<!\\S)" + FIELD_LABEL_REGEX + "\\s*[:：]|$)");

    private final PdfConversionEngine engine;
    private final HybridOptionsResolver hybridOptionsResolver;
    private final ObjectMapper objectMapper;

    public PdfStructuredPiiDetector(PdfConversionEngine engine,
                                    HybridOptionsResolver hybridOptionsResolver,
                                    ObjectMapper objectMapper) {
        this.engine = engine;
        this.hybridOptionsResolver = hybridOptionsResolver;
        this.objectMapper = objectMapper;
    }

    public PiiDetectionResult detect(UUID documentId,
                                     String originalFilename,
                                     String contentType,
                                     Path sourceFile,
                                     Path workingDirectory) {
        try {
            Path outputDirectory = workingDirectory.resolve("odl-json");
            Files.createDirectories(outputDirectory);
            PdfConversionOptions detectionOptions = hybridOptionsResolver.applyDefaults(new PdfConversionOptions(
                    List.of("json"),
                    null,
                    null,
                    false,
                    false,
                    "xycut",
                    "default",
                    "off",
                    "png",
                    null,
                    false,
                    false,
                    "off",
                    "auto",
                    null,
                    null,
                    true
            ), HybridUsage.PII_DETECTION);
            engine.convert(sourceFile, outputDirectory, detectionOptions);

            Path jsonFile = Files.list(outputDirectory)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("OpenDataLoader JSON output not found"));

            JsonNode root = objectMapper.readTree(jsonFile.toFile());
            int pageCount = root.path("number of pages").asInt(1);
            List<TextSegment> segments = new ArrayList<>();
            collectSegments(root, segments);

            Map<String, PiiFinding> unique = new LinkedHashMap<>();
            for (TextSegment segment : segments) {
                for (PiiTextMatch match : PiiPatternMatcher.findMatches(segment.text())) {
                    PiiFinding finding = new PiiFinding(
                            match.type(),
                            match.label(),
                            match.originalText(),
                            match.maskedText(),
                            segment.pageNumber(),
                            segment.boundingBox(),
                            "pdf-structured"
                    );
                    String key = finding.type() + "|" + finding.pageNumber() + "|" + finding.originalText()
                            + "|" + finding.boundingBox().x() + "|" + finding.boundingBox().y();
                    unique.putIfAbsent(key, finding);
                }
            }

            List<PiiFinding> findings = unique.values().stream()
                    .sorted(Comparator.comparingInt(PiiFinding::pageNumber)
                            .thenComparing(f -> f.boundingBox().y())
                            .thenComparing(f -> f.boundingBox().x()))
                    .toList();

            return new PiiDetectionResult(
                    documentId,
                    originalFilename,
                    contentType,
                    "pdf",
                    pageCount,
                    sourceFile,
                    findings
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to analyze PDF for PII", ex);
        }
    }

    private void collectSegments(JsonNode node, List<TextSegment> segments) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if (node.has("content") && node.has("bounding box") && node.has("page number")) {
                String content = node.path("content").asText("");
                JsonNode bboxNode = node.path("bounding box");
                if (!content.isBlank() && bboxNode.isArray() && bboxNode.size() >= 4) {
                    double x1 = bboxNode.get(0).asDouble();
                    double y1 = bboxNode.get(1).asDouble();
                    double x2 = bboxNode.get(2).asDouble();
                    double y2 = bboxNode.get(3).asDouble();
                    segments.addAll(splitStructuredSegment(
                            node.path("page number").asInt(1),
                            content,
                            new PiiBoundingBox(
                                    Math.min(x1, x2),
                                    Math.min(y1, y2),
                                    Math.abs(x2 - x1),
                                    Math.abs(y2 - y1)
                            )
                    ));
                }
            }
            node.fields().forEachRemaining(entry -> collectSegments(entry.getValue(), segments));
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectSegments(child, segments));
        }
    }

    private List<TextSegment> splitStructuredSegment(int pageNumber, String content, PiiBoundingBox bbox) {
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return List.of();
        }

        Matcher matcher = FIELD_CHUNK_PATTERN.matcher(normalized);
        List<String> chunks = new ArrayList<>();
        int firstMatchStart = -1;
        while (matcher.find()) {
            if (firstMatchStart < 0) {
                firstMatchStart = matcher.start(1);
            }
            chunks.add(matcher.group(1).trim());
        }
        if (chunks.isEmpty()) {
            return List.of(new TextSegment(pageNumber, normalized, bbox));
        }

        String prefix = firstMatchStart <= 0 ? "" : normalized.substring(0, firstMatchStart).trim();
        int preambleCount = prefix.isBlank() ? 0 : 1;
        double unitHeight = bbox.height() / (chunks.size() + preambleCount);
        double x = bbox.x();
        double width = bbox.width();

        List<TextSegment> segments = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            double y = bbox.y() + bbox.height() - unitHeight * (index + preambleCount + 1);
            segments.add(new TextSegment(
                    pageNumber,
                    chunks.get(index),
                    new PiiBoundingBox(x, y, width, unitHeight)
            ));
        }
        return segments;
    }

    private record TextSegment(
            int pageNumber,
            String text,
            PiiBoundingBox boundingBox
    ) {
    }
}

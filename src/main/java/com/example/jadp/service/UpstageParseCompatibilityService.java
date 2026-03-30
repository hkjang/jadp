package com.example.jadp.service;

import com.example.jadp.dto.UpstageParseContent;
import com.example.jadp.dto.UpstageParseCoordinate;
import com.example.jadp.dto.UpstageParseElement;
import com.example.jadp.dto.UpstageParseRequest;
import com.example.jadp.dto.UpstageParseResponse;
import com.example.jadp.dto.UpstageParseUsage;
import com.example.jadp.model.ConversionJob;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.JobStatus;
import com.example.jadp.support.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class UpstageParseCompatibilityService {

    private final PdfJobService pdfJobService;
    private final ObjectMapper objectMapper;

    public UpstageParseCompatibilityService(PdfJobService pdfJobService, ObjectMapper objectMapper) {
        this.pdfJobService = pdfJobService;
        this.objectMapper = objectMapper;
    }

    public UpstageParseResponse parse(MultipartFile document, UpstageParseRequest request) {
        if (document == null || document.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A document file is required.");
        }

        ConversionJob job = pdfJobService.convertSync(document, request.toPdfOptions());
        if (job.getStatus() == JobStatus.FAILED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, job.getError());
        }

        try {
            GeneratedArtifact jsonArtifact = requireArtifact(job, "json");
            JsonNode root = objectMapper.readTree(jsonArtifact.absolutePath().toFile());
            Map<Integer, PageDimensions> pageDimensions = loadPageDimensions(job.getUploadPath());

            List<UpstageParseElement> elements = new ArrayList<>();
            AtomicInteger ids = new AtomicInteger();
            collectElements(root, pageDimensions, ids, elements);

            return new UpstageParseResponse(
                    "2.0",
                    new UpstageParseContent(
                            readArtifact(job, "html"),
                            readArtifact(job, "markdown"),
                            readArtifact(job, "text")
                    ),
                    elements,
                    request.effectiveModel(),
                    new UpstageParseUsage(pageDimensions.size())
            );
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to adapt OpenDataLoader output to the compatibility response.", ex);
        }
    }

    private void collectElements(JsonNode node,
                                 Map<Integer, PageDimensions> pageDimensions,
                                 AtomicInteger ids,
                                 List<UpstageParseElement> elements) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectElements(child, pageDimensions, ids, elements));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String category = mapCategory(node.path("type").asText(""));
        if (category != null && hasBoundingBox(node) && node.path("page number").canConvertToInt()) {
            int pageNumber = node.path("page number").asInt(1);
            PageDimensions dimensions = pageDimensions.get(pageNumber);
            if (dimensions != null) {
                int elementId = ids.getAndIncrement();
                elements.add(new UpstageParseElement(
                        category,
                        buildElementContent(category, extractText(node), elementId),
                        toCoordinates(node.path("bounding box"), dimensions),
                        elementId,
                        pageNumber
                ));
            }
        }

        node.fields().forEachRemaining(entry -> collectElements(entry.getValue(), pageDimensions, ids, elements));
    }

    private UpstageParseContent buildElementContent(String category, String text, int id) {
        String normalizedText = normalizeText(text);
        String html = switch (category) {
            case "heading1" -> wrapTag("h1", id, normalizedText, null);
            case "header" -> wrapTag("header", id, normalizedText, null);
            case "footer" -> wrapTag("footer", id, normalizedText, null);
            case "caption" -> wrapTag("caption", id, normalizedText, null);
            case "table" -> "<table id='" + id + "'><tr><td>" + escapeInline(normalizedText) + "</td></tr></table>";
            case "figure" -> "<figure id='" + id + "'></figure>";
            case "chart" -> "<figure id='" + id + "'><img data-category='chart' alt='' /></figure>";
            default -> wrapTag("p", id, normalizedText, category);
        };
        return new UpstageParseContent(
                html,
                toMarkdown(category, normalizedText),
                normalizedText
        );
    }

    private String wrapTag(String tagName, int id, String text, String dataCategory) {
        StringBuilder builder = new StringBuilder();
        builder.append("<").append(tagName).append(" id='").append(id).append("'");
        if (StringUtils.hasText(dataCategory)) {
            builder.append(" data-category='").append(dataCategory).append("'");
        }
        builder.append(">");
        builder.append(escapeInline(text));
        builder.append("</").append(tagName).append(">");
        return builder.toString();
    }

    private String toMarkdown(String category, String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return switch (category) {
            case "heading1" -> "# " + text;
            case "list" -> List.of(text.split("\\R+")).stream()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .map(line -> "- " + line)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            case "figure", "chart" -> "";
            default -> text;
        };
    }

    private String extractText(JsonNode node) {
        String directContent = normalizeText(node.path("content").asText(""));
        if (StringUtils.hasText(directContent)) {
            return directContent;
        }

        List<String> parts = new ArrayList<>();
        collectLeafText(node.path("kids"), parts);
        return String.join("\n", parts);
    }

    private void collectLeafText(JsonNode node, List<String> parts) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectLeafText(child, parts));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        JsonNode kids = node.path("kids");
        String type = node.path("type").asText("").toLowerCase(Locale.ROOT);
        String content = normalizeText(node.path("content").asText(""));
        boolean leafLike = !kids.isArray() || kids.isEmpty() || "text chunk".equals(type)
                || "table cell".equals(type) || "list item".equals(type);
        if (leafLike && StringUtils.hasText(content)) {
            parts.add(content);
            return;
        }
        collectLeafText(kids, parts);
    }

    private List<UpstageParseCoordinate> toCoordinates(JsonNode boundingBox, PageDimensions dimensions) {
        double x1 = boundingBox.get(0).asDouble();
        double y1 = boundingBox.get(1).asDouble();
        double x2 = boundingBox.get(2).asDouble();
        double y2 = boundingBox.get(3).asDouble();

        double left = Math.min(x1, x2);
        double right = Math.max(x1, x2);
        double lower = Math.min(y1, y2);
        double upper = Math.max(y1, y2);

        double top = dimensions.height() - upper;
        double bottom = dimensions.height() - lower;

        return List.of(
                new UpstageParseCoordinate(round4(left / dimensions.width()), round4(top / dimensions.height())),
                new UpstageParseCoordinate(round4(right / dimensions.width()), round4(top / dimensions.height())),
                new UpstageParseCoordinate(round4(right / dimensions.width()), round4(bottom / dimensions.height())),
                new UpstageParseCoordinate(round4(left / dimensions.width()), round4(bottom / dimensions.height()))
        );
    }

    private Map<Integer, PageDimensions> loadPageDimensions(Path pdfPath) throws IOException {
        Map<Integer, PageDimensions> pages = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDRectangle box = document.getPage(index).getCropBox();
                if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
                    box = document.getPage(index).getMediaBox();
                }
                pages.put(index + 1, new PageDimensions(box.getWidth(), box.getHeight()));
            }
        }
        return pages;
    }

    private GeneratedArtifact requireArtifact(ConversionJob job, String format) {
        return job.getArtifacts().stream()
                .filter(artifact -> format.equals(artifact.format()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Expected " + format + " output was not generated."));
    }

    private String readArtifact(ConversionJob job, String format) {
        return job.getArtifacts().stream()
                .filter(artifact -> format.equals(artifact.format()))
                .findFirst()
                .map(artifact -> readString(artifact.absolutePath()))
                .orElse("");
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read generated artifact: " + path.getFileName(), ex);
        }
    }

    private boolean hasBoundingBox(JsonNode node) {
        JsonNode bbox = node.path("bounding box");
        return bbox.isArray() && bbox.size() >= 4;
    }

    private String mapCategory(String type) {
        String normalized = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "heading", "heading1", "title" -> "heading1";
            case "header" -> "header";
            case "footer" -> "footer";
            case "caption" -> "caption";
            case "paragraph" -> "paragraph";
            case "equation" -> "equation";
            case "list" -> "list";
            case "index" -> "index";
            case "footnote" -> "footnote";
            case "table" -> "table";
            case "figure", "image" -> "figure";
            case "chart" -> "chart";
            default -> null;
        };
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String escapeInline(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value).replace("\n", "<br>");
    }

    private double round4(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private record PageDimensions(double width, double height) {
    }
}

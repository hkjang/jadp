package com.example.jadp.service;

import com.example.jadp.dto.UpstageParseContent;
import com.example.jadp.dto.UpstageParseCoordinate;
import com.example.jadp.dto.UpstageParseElement;
import com.example.jadp.dto.UpstageParseResponse;
import com.example.jadp.dto.UpstageParseUsage;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class HybridDoclingParseService {

    private final HybridDoclingDirectPiiDetector hybridDoclingDirectPiiDetector;

    public HybridDoclingParseService(HybridDoclingDirectPiiDetector hybridDoclingDirectPiiDetector) {
        this.hybridDoclingDirectPiiDetector = hybridDoclingDirectPiiDetector;
    }

    public boolean isConfigured() {
        return hybridDoclingDirectPiiDetector.isConfigured();
    }

    public UpstageParseResponse parse(Path sourceFile,
                                      String contentType,
                                      String model,
                                      boolean forceOcr) throws IOException, InterruptedException {
        JsonNode root = hybridDoclingDirectPiiDetector.convertDocument(sourceFile, contentType, forceOcr);
        JsonNode documentJson = root.path("document").path("json_content");
        Map<Integer, PageDimensions> pageDimensions = loadPageDimensions(documentJson.path("pages"));

        AtomicInteger ids = new AtomicInteger();
        List<ElementCandidate> candidates = new ArrayList<>();
        collectTextCandidates(documentJson.path("texts"), pageDimensions, ids, candidates);
        collectTableCandidates(documentJson.path("tables"), pageDimensions, ids, candidates);
        collectPictureCandidates(documentJson.path("pictures"), pageDimensions, ids, candidates);

        candidates.sort(Comparator.comparingInt(ElementCandidate::page)
                .thenComparingDouble(ElementCandidate::top)
                .thenComparingDouble(ElementCandidate::left)
                .thenComparingInt(candidate -> candidate.element().id()));

        List<UpstageParseElement> elements = candidates.stream()
                .map(ElementCandidate::element)
                .toList();
        return new UpstageParseResponse(
                "2.0",
                aggregateContent(elements),
                elements,
                StringUtils.hasText(model) ? model : "document-parse",
                new UpstageParseUsage(pageDimensions.size())
        );
    }

    private void collectTextCandidates(JsonNode textsNode,
                                       Map<Integer, PageDimensions> pageDimensions,
                                       AtomicInteger ids,
                                       List<ElementCandidate> candidates) {
        if (!textsNode.isArray()) {
            return;
        }
        for (JsonNode textNode : textsNode) {
            String text = normalizeText(textNode.path("text").asText(""));
            if (!StringUtils.hasText(text)) {
                continue;
            }
            JsonNode prov = textNode.path("prov").path(0);
            int page = prov.path("page_no").asInt(1);
            PageDimensions dimensions = pageDimensions.get(page);
            if (dimensions == null) {
                continue;
            }
            BoundingShape shape = toShape(prov.path("bbox"), dimensions);
            int id = ids.getAndIncrement();
            UpstageParseContent content = new UpstageParseContent(
                    wrapTag("p", id, text, "paragraph"),
                    text,
                    text
            );
            candidates.add(new ElementCandidate(
                    new UpstageParseElement("paragraph", content, shape.coordinates(), id, page),
                    page,
                    shape.top(),
                    shape.left()
            ));
        }
    }

    private void collectTableCandidates(JsonNode tablesNode,
                                        Map<Integer, PageDimensions> pageDimensions,
                                        AtomicInteger ids,
                                        List<ElementCandidate> candidates) {
        if (!tablesNode.isArray()) {
            return;
        }
        for (JsonNode tableNode : tablesNode) {
            JsonNode prov = tableNode.path("prov").path(0);
            int page = prov.path("page_no").asInt(1);
            PageDimensions dimensions = pageDimensions.get(page);
            if (dimensions == null) {
                continue;
            }

            TableContent tableContent = buildTableContent(tableNode.path("data").path("table_cells"));
            if (!tableContent.hasContent()) {
                continue;
            }
            BoundingShape shape = toShape(prov.path("bbox"), dimensions);
            int id = ids.getAndIncrement();
            UpstageParseContent content = new UpstageParseContent(
                    tableContent.html(id),
                    tableContent.markdown(),
                    tableContent.text()
            );
            candidates.add(new ElementCandidate(
                    new UpstageParseElement("table", content, shape.coordinates(), id, page),
                    page,
                    shape.top(),
                    shape.left()
            ));
        }
    }

    private void collectPictureCandidates(JsonNode picturesNode,
                                          Map<Integer, PageDimensions> pageDimensions,
                                          AtomicInteger ids,
                                          List<ElementCandidate> candidates) {
        if (!picturesNode.isArray()) {
            return;
        }
        for (JsonNode pictureNode : picturesNode) {
            if (pictureNode.path("children").isArray() && !pictureNode.path("children").isEmpty()) {
                continue;
            }
            JsonNode prov = pictureNode.path("prov").path(0);
            int page = prov.path("page_no").asInt(1);
            PageDimensions dimensions = pageDimensions.get(page);
            if (dimensions == null) {
                continue;
            }
            BoundingShape shape = toShape(prov.path("bbox"), dimensions);
            int id = ids.getAndIncrement();
            UpstageParseContent content = new UpstageParseContent(
                    "<figure id='" + id + "'></figure>",
                    "",
                    ""
            );
            candidates.add(new ElementCandidate(
                    new UpstageParseElement("figure", content, shape.coordinates(), id, page),
                    page,
                    shape.top(),
                    shape.left()
            ));
        }
    }

    private TableContent buildTableContent(JsonNode cellsNode) {
        if (!cellsNode.isArray() || cellsNode.isEmpty()) {
            return TableContent.empty();
        }

        Map<Integer, Map<Integer, String>> rows = new LinkedHashMap<>();
        int maxColumn = -1;
        for (JsonNode cellNode : cellsNode) {
            String text = normalizeText(cellNode.path("text").asText(""));
            if (!StringUtils.hasText(text)) {
                continue;
            }
            int row = cellNode.path("start_row_offset_idx").asInt(rows.size());
            int column = cellNode.path("start_col_offset_idx").asInt(0);
            rows.computeIfAbsent(row, ignored -> new LinkedHashMap<>()).put(column, text);
            maxColumn = Math.max(maxColumn, column);
        }
        if (rows.isEmpty()) {
            return TableContent.empty();
        }

        int columnCount = maxColumn + 1;
        List<List<String>> matrix = new ArrayList<>();
        for (Map<Integer, String> row : rows.values()) {
            List<String> values = new ArrayList<>();
            for (int column = 0; column < columnCount; column++) {
                values.add(row.getOrDefault(column, ""));
            }
            matrix.add(values);
        }
        return new TableContent(matrix);
    }

    private UpstageParseContent aggregateContent(List<UpstageParseElement> elements) {
        List<String> htmlParts = new ArrayList<>();
        List<String> markdownParts = new ArrayList<>();
        List<String> textParts = new ArrayList<>();

        for (UpstageParseElement element : elements) {
            if (StringUtils.hasText(element.content().html())) {
                htmlParts.add(element.content().html());
            }
            if (StringUtils.hasText(element.content().markdown())) {
                markdownParts.add(element.content().markdown());
            }
            if (StringUtils.hasText(element.content().text())) {
                textParts.add(element.content().text());
            }
        }

        String html = "<!DOCTYPE html>\n<html lang=\"ko\">\n<head>\n<meta charset=\"utf-8\">\n<title>document-digitization</title>\n</head>\n<body>\n"
                + String.join("\n\n", htmlParts)
                + "\n</body>\n</html>";
        return new UpstageParseContent(
                html,
                String.join("\n\n", markdownParts),
                String.join("\n\n", textParts)
        );
    }

    private Map<Integer, PageDimensions> loadPageDimensions(JsonNode pagesNode) {
        Map<Integer, PageDimensions> pages = new LinkedHashMap<>();
        pagesNode.fields().forEachRemaining(entry -> pages.put(
                Integer.parseInt(entry.getKey()),
                new PageDimensions(
                        entry.getValue().path("size").path("width").asDouble(),
                        entry.getValue().path("size").path("height").asDouble()
                )
        ));
        return pages;
    }

    private BoundingShape toShape(JsonNode bboxNode, PageDimensions dimensions) {
        double left = bboxNode.path("l").asDouble();
        double right = bboxNode.path("r").asDouble();
        double topValue = bboxNode.path("t").asDouble();
        double bottomValue = bboxNode.path("b").asDouble();
        String origin = bboxNode.path("coord_origin").asText("TOPLEFT").toUpperCase(Locale.ROOT);

        double top = "BOTTOMLEFT".equals(origin)
                ? dimensions.height() - Math.max(topValue, bottomValue)
                : Math.min(topValue, bottomValue);
        double bottom = "BOTTOMLEFT".equals(origin)
                ? dimensions.height() - Math.min(topValue, bottomValue)
                : Math.max(topValue, bottomValue);

        double normalizedLeft = normalizeRatio(left, dimensions.width());
        double normalizedRight = normalizeRatio(right, dimensions.width());
        double normalizedTop = normalizeRatio(top, dimensions.height());
        double normalizedBottom = normalizeRatio(bottom, dimensions.height());

        return new BoundingShape(
                List.of(
                        new UpstageParseCoordinate(normalizedLeft, normalizedTop),
                        new UpstageParseCoordinate(normalizedRight, normalizedTop),
                        new UpstageParseCoordinate(normalizedRight, normalizedBottom),
                        new UpstageParseCoordinate(normalizedLeft, normalizedBottom)
                ),
                top,
                left
        );
    }

    private String wrapTag(String tagName, int id, String text, String category) {
        StringBuilder builder = new StringBuilder();
        builder.append("<").append(tagName).append(" id='").append(id).append("'");
        if (StringUtils.hasText(category)) {
            builder.append(" data-category='").append(category).append("'");
        }
        builder.append(">");
        builder.append(HtmlUtils.htmlEscape(text).replace("\n", "<br>"));
        builder.append("</").append(tagName).append(">");
        return builder.toString();
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

    private double normalizeRatio(double value, double max) {
        if (max <= 0d) {
            return 0d;
        }
        double ratio = value / max;
        double clamped = Math.max(0d, Math.min(1d, ratio));
        return Math.round(clamped * 10_000d) / 10_000d;
    }

    private record PageDimensions(double width, double height) {
    }

    private record BoundingShape(List<UpstageParseCoordinate> coordinates, double top, double left) {
    }

    private record ElementCandidate(UpstageParseElement element, int page, double top, double left) {
    }

    private record TableContent(List<List<String>> rows) {

        static TableContent empty() {
            return new TableContent(List.of());
        }

        boolean hasContent() {
            return !rows.isEmpty();
        }

        String text() {
            return rows.stream()
                    .map(row -> row.stream()
                            .filter(StringUtils::hasText)
                            .reduce((left, right) -> left + " | " + right)
                            .orElse(""))
                    .filter(StringUtils::hasText)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        String markdown() {
            if (rows.isEmpty()) {
                return "";
            }
            if (rows.size() == 1 || rows.get(0).size() <= 1) {
                return text();
            }
            List<String> header = rows.get(0).stream().map(this::escapePipe).toList();
            String separator = header.stream().map(ignored -> "---").reduce((left, right) -> left + " | " + right).orElse("---");
            List<String> lines = new ArrayList<>();
            lines.add("| " + String.join(" | ", header) + " |");
            lines.add("| " + separator + " |");
            for (int index = 1; index < rows.size(); index++) {
                lines.add("| " + String.join(" | ", rows.get(index).stream().map(this::escapePipe).toList()) + " |");
            }
            return String.join("\n", lines);
        }

        String html(int id) {
            StringBuilder builder = new StringBuilder();
            builder.append("<table id='").append(id).append("'>");
            for (List<String> row : rows) {
                builder.append("<tr>");
                for (String cell : row) {
                    builder.append("<td>").append(HtmlUtils.htmlEscape(cell)).append("</td>");
                }
                builder.append("</tr>");
            }
            builder.append("</table>");
            return builder.toString();
        }

        private String escapePipe(String value) {
            return value.replace("|", "\\|");
        }
    }
}

package com.example.jadp.service;

import com.example.jadp.config.HybridProcessingProperties;
import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiType;
import com.example.jadp.support.ImagePdfSupport;
import com.example.jadp.support.PdfImageContentDetector;
import com.example.jadp.support.PiiFindingMergeSupport;
import com.example.jadp.support.PiiMaskingRules;
import com.example.jadp.support.PiiPatternMatcher;
import com.example.jadp.support.PiiTextMatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class HybridDoclingDirectPiiDetector {

    private static final Logger log = LoggerFactory.getLogger(HybridDoclingDirectPiiDetector.class);

    private static final float TILE_RENDER_DPI = 144f;
    /** DPI used when rendering a full page image for direct docling OCR. */
    private static final float IMAGE_PAGE_DPI = 200f;
    private static final int TILE_TARGET_WIDTH = 2_048;
    private static final int MAX_TILE_CALLS = 10;
    private static final int TILE_BAND_COUNT = 4;
    /** Maximum total elapsed time for the entire detect operation (convert + tiles). */
    private static final long MAX_DETECT_ELAPSED_MILLIS = 180_000L;

    private final HybridProcessingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HybridDoclingDirectPiiDetector(HybridOptionsResolver hybridOptionsResolver, ObjectMapper objectMapper) {
        this.properties = hybridOptionsResolver.properties();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && StringUtils.hasText(properties.getUrl())
                && "docling-fast".equalsIgnoreCase(properties.getBackend());
    }

    public List<PiiFinding> detect(Path pdfFile, Path workingDirectory) {
        return detectPdf(pdfFile, workingDirectory);
    }

    public List<PiiFinding> detectPdf(Path pdfFile, Path workingDirectory) {
        if (!isConfigured()) {
            return List.of();
        }
        // Detect image-based pages upfront and use dedicated OCR path when found.
        List<Integer> imagePageIndices = imageBasedPageIndices(pdfFile);
        if (!imagePageIndices.isEmpty()) {
            log.info("[PII-HYBRID] {} image-based page(s) detected in '{}' – using image-aware OCR path",
                    imagePageIndices.size(), pdfFile.getFileName());
            return detectPdfImageAware(pdfFile, imagePageIndices, workingDirectory);
        }
        return detectDirect(pdfFile, "application/pdf", workingDirectory, CoordinateSpace.PDF, "hybrid-direct-docling.json");
    }

    /**
     * Image-aware PDF PII detection:
     * <ol>
     *   <li>Send the whole PDF to docling with {@code force_full_page_ocr=true} to get
     *       a baseline OCR result for all pages.</li>
     *   <li>For every image-based page render a high-resolution PNG (200 DPI) and
     *       send it to docling individually so that fine-grained OCR runs on the
     *       actual raster content.</li>
     *   <li>Merge all findings, deduplicating overlapping bounding boxes.</li>
     * </ol>
     */
    private List<PiiFinding> detectPdfImageAware(Path pdfFile,
                                                  List<Integer> imagePageIndices,
                                                  Path workingDirectory) {
        long startTime = System.currentTimeMillis();
        List<PiiFinding> allFindings = new ArrayList<>();
        try {
            // ── Step 1: whole-PDF OCR with force_full_page_ocr ──────────────
            log.info("[PII-HYBRID] Sending '{}' to docling (force_full_page_ocr=true)", pdfFile.getFileName());
            JsonNode root = convert(pdfFile, "application/pdf", true);
            JsonNode documentJson = root.path("document").path("json_content");
            Files.writeString(
                    workingDirectory.resolve("hybrid-direct-docling.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            allFindings.addAll(extractFindings(documentJson, CoordinateSpace.PDF));
            log.info("[PII-HYBRID] Whole-PDF OCR: {} findings from {} text elements in {}ms",
                    allFindings.size(), documentJson.path("texts").size(),
                    System.currentTimeMillis() - startTime);

            // ── Step 2: per-page high-res OCR for each image-based page ─────
            Path pageImageDir = workingDirectory.resolve("hybrid-page-images");
            Files.createDirectories(pageImageDir);

            try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int pageIndex : imagePageIndices) {
                    if (System.currentTimeMillis() - startTime >= MAX_DETECT_ELAPSED_MILLIS) {
                        log.warn("[PII-HYBRID] Time limit reached – stopping per-page OCR at page {}",
                                pageIndex + 1);
                        break;
                    }
                    int pageNumber = pageIndex + 1;
                    log.info("[PII-HYBRID] Per-page OCR: rendering page {} at {}dpi", pageNumber, (int) IMAGE_PAGE_DPI);
                    BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, IMAGE_PAGE_DPI, ImageType.RGB);
                    Path pagePng = pageImageDir.resolve("page-" + pageNumber + ".png");
                    ImageIO.write(pageImage, "PNG", pagePng.toFile());

                    List<PiiFinding> pageFindings = detectImagePageForPdf(pageNumber, pagePng, workingDirectory);
                    log.info("[PII-HYBRID] Page {} per-page OCR: {} findings", pageNumber, pageFindings.size());
                    allFindings = new ArrayList<>(PiiFindingMergeSupport.mergeFindings(allFindings, pageFindings));
                }
            }

            log.info("[PII-HYBRID] Image-aware detection complete: {} findings in {}ms",
                    allFindings.size(), System.currentTimeMillis() - startTime);
            return allFindings;

        } catch (Exception ex) {
            log.error("[PII-HYBRID] Image-aware PDF detection failed after {}ms: {}",
                    System.currentTimeMillis() - startTime, ex.getMessage(), ex);
            return allFindings.isEmpty() ? List.of() : allFindings;
        }
    }

    /**
     * Sends a single rendered page PNG to docling, extracts findings, and
     * remaps the page number to the actual PDF page number.
     */
    private List<PiiFinding> detectImagePageForPdf(int pageNumber, Path pagePng, Path workingDirectory)
            throws IOException, InterruptedException {
        JsonNode pageRoot = convert(pagePng, "image/png");
        JsonNode pageJson = pageRoot.path("document").path("json_content");
        Files.writeString(
                workingDirectory.resolve("hybrid-page-" + pageNumber + ".json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(pageRoot),
                StandardCharsets.UTF_8);

        // extractFindings uses pageNo from docling's response (always 1 for a single PNG).
        // Override with the actual PDF page number.
        List<PiiFinding> findings = extractFindings(pageJson, CoordinateSpace.IMAGE);
        return findings.stream()
                .map(f -> new PiiFinding(
                        f.type(), f.label(), f.originalText(), f.maskedText(),
                        pageNumber, f.boundingBox(),
                        f.detectionSource() + "-page-ocr"))
                .toList();
    }

    /** Returns 0-based image-page indices for {@code pdfFile}, or empty list on error. */
    private List<Integer> imageBasedPageIndices(Path pdfFile) {
        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            return PdfImageContentDetector.imageBasedPageIndices(doc);
        } catch (IOException ex) {
            log.warn("[PII-HYBRID] Could not check image pages in '{}': {}", pdfFile.getFileName(), ex.getMessage());
            return List.of();
        }
    }

    public List<PiiFinding> detectImage(Path imageFile, String contentType, Path workingDirectory) {
        String mimeType = StringUtils.hasText(contentType) ? contentType : probeContentType(imageFile);
        return detectDirect(imageFile, mimeType, workingDirectory, CoordinateSpace.IMAGE, "hybrid-direct-image.json");
    }

    private List<PiiFinding> detectDirect(Path sourceFile,
                                          String contentType,
                                          Path workingDirectory,
                                          CoordinateSpace coordinateSpace,
                                          String responseFilename) {
        if (!isConfigured()) {
            return List.of();
        }
        long startTime = System.currentTimeMillis();
        try {
            log.info("[PII-HYBRID] Starting direct detect for {} ({})", sourceFile.getFileName(), contentType);
            JsonNode root = convert(sourceFile, contentType);
            long convertElapsed = System.currentTimeMillis() - startTime;
            log.info("[PII-HYBRID] Initial convert completed in {}ms", convertElapsed);

            JsonNode documentJson = root.path("document").path("json_content");
            Path directResponse = workingDirectory.resolve(responseFilename);
            Files.writeString(directResponse,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);

            List<PiiFinding> findings = new ArrayList<>(extractFindings(documentJson, coordinateSpace));
            log.info("[PII-HYBRID] Extracted {} initial findings", findings.size());

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= MAX_DETECT_ELAPSED_MILLIS) {
                log.warn("[PII-HYBRID] Skipping tile detection – elapsed {}ms exceeds limit {}ms", elapsed, MAX_DETECT_ELAPSED_MILLIS);
                return findings;
            }

            List<PiiFinding> tileFindings = coordinateSpace == CoordinateSpace.PDF
                    ? detectPdfRegionTiles(sourceFile, workingDirectory, documentJson, findings.size(), startTime)
                    : detectImageRegionTiles(sourceFile, contentType, workingDirectory, documentJson, findings.size(), startTime);
            long totalElapsed = System.currentTimeMillis() - startTime;
            log.info("[PII-HYBRID] Detect completed: {} findings + {} tile findings in {}ms",
                    findings.size(), tileFindings.size(), totalElapsed);
            return PiiFindingMergeSupport.mergeFindings(findings, tileFindings);
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[PII-HYBRID] Detection failed after {}ms: {}", elapsed, ex.getMessage(), ex);
            return List.of();
        }
    }

    private JsonNode convert(Path uploadFile, String contentType) throws IOException, InterruptedException {
        return convert(uploadFile, contentType, false);
    }

    /**
     * Calls the docling {@code /v1/convert/file} endpoint.
     *
     * @param forceOcr when {@code true} adds a JSON options part with
     *                 {@code force_full_page_ocr: true, do_ocr: true} so that
     *                 image-based pages are guaranteed to be OCR-processed.
     */
    private JsonNode convert(Path uploadFile, String contentType, boolean forceOcr)
            throws IOException, InterruptedException {
        String boundary = "----jadp-" + UUID.randomUUID();
        byte[] payload = multipartPayload(uploadFile, boundary, contentType, forceOcr);
        long callStart = System.currentTimeMillis();
        log.debug("[PII-HYBRID] Calling hybrid convert: {} ({} bytes, timeout={}ms, forceOcr={})",
                uploadFile.getFileName(), payload.length, properties.getTimeoutMillis(), forceOcr);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getUrl()) + "/v1/convert/file"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofMillis(properties.getTimeoutMillis()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long callElapsed = System.currentTimeMillis() - callStart;
        if (response.statusCode() >= 400) {
            log.error("[PII-HYBRID] Hybrid convert failed: status={} elapsed={}ms file={}",
                    response.statusCode(), callElapsed, uploadFile.getFileName());
            throw new IOException("Hybrid direct request failed with status " + response.statusCode());
        }
        log.debug("[PII-HYBRID] Hybrid convert OK: status={} elapsed={}ms forceOcr={}",
                response.statusCode(), callElapsed, forceOcr);
        return objectMapper.readTree(response.body());
    }

    private byte[] multipartPayload(Path uploadFile, String boundary, String contentType) throws IOException {
        return multipartPayload(uploadFile, boundary, contentType, false);
    }

    private byte[] multipartPayload(Path uploadFile, String boundary, String contentType, boolean forceOcr)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // ── file part ────────────────────────────────────────────────────────
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"files\"; filename=\""
                + uploadFile.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(uploadFile));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));

        // ── options part (force OCR) ─────────────────────────────────────────
        if (forceOcr) {
            // Both field names are included for compatibility across docling versions.
            String optionsJson = "{\"force_full_page_ocr\":true,\"do_ocr\":true}";
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Disposition: form-data; name=\"options\"\r\n".getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/json\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(optionsJson.getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private List<PiiFinding> extractFindings(JsonNode documentJson, CoordinateSpace coordinateSpace) {
        Map<Integer, Double> pageHeights = pageHeights(documentJson.path("pages"));
        List<PiiFinding> findings = new ArrayList<>();

        for (JsonNode textNode : documentJson.path("texts")) {
            addTextNodeFindings(findings, textNode.path("text").asText(""), textNode.path("prov"), pageHeights,
                    "hybrid-direct-text", coordinateSpace);
        }
        for (JsonNode tableNode : documentJson.path("tables")) {
            List<TableCell> tableCells = parseTableCells(tableNode, pageHeights, coordinateSpace);
            addTableContextFindings(findings, tableCells, "hybrid-direct-table-context");
            for (TableCell tableCell : tableCells) {
                addTextFindings(findings, tableCell.text(), tableCell.pageNumber(), tableCell.boundingBox(),
                        "hybrid-direct-table");
            }
        }
        for (JsonNode kvNode : documentJson.path("key_value_items")) {
            addTextNodeFindings(findings, kvNode.path("text").asText(""), kvNode.path("prov"), pageHeights,
                    "hybrid-direct-kv", coordinateSpace);
        }
        return findings;
    }

    private List<TableCell> parseTableCells(JsonNode tableNode,
                                            Map<Integer, Double> pageHeights,
                                            CoordinateSpace coordinateSpace) {
        List<TableCell> cells = new ArrayList<>();
        int fallbackPage = tableNode.path("prov").path(0).path("page_no").asInt(1);
        int fallbackIndex = 0;
        for (JsonNode cellNode : tableNode.path("data").path("table_cells")) {
            String text = cellNode.path("text").asText("").trim();
            if (!StringUtils.hasText(text) || cellNode.path("bbox").isMissingNode()) {
                fallbackIndex++;
                continue;
            }
            int pageNumber = cellNode.path("page_no").asInt(fallbackPage);
            double pageHeight = pageHeights.getOrDefault(pageNumber, 0d);
            PiiBoundingBox bbox = toBoundingBox(cellNode.path("bbox"), pageHeight, coordinateSpace);
            cells.add(new TableCell(
                    pageNumber,
                    cellNode.path("start_row_offset_idx").asInt(fallbackIndex),
                    cellNode.path("start_col_offset_idx").asInt(fallbackIndex),
                    text,
                    bbox
            ));
            fallbackIndex++;
        }
        return cells.stream()
                .sorted(Comparator.comparingInt(TableCell::pageNumber)
                        .thenComparingInt(TableCell::row)
                        .thenComparingInt(TableCell::column))
                .toList();
    }

    private void addTableContextFindings(List<PiiFinding> findings,
                                         List<TableCell> tableCells,
                                         String detectionSource) {
        Map<Integer, List<TableCell>> byPage = tableCells.stream()
                .collect(Collectors.groupingBy(TableCell::pageNumber, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<Integer, List<TableCell>> entry : byPage.entrySet()) {
            Map<Integer, List<TableCell>> byRow = entry.getValue().stream()
                    .collect(Collectors.groupingBy(TableCell::row, LinkedHashMap::new, Collectors.toList()));
            Map<Integer, List<TableCell>> byColumn = entry.getValue().stream()
                    .collect(Collectors.groupingBy(TableCell::column, LinkedHashMap::new, Collectors.toList()));

            for (TableCell labelCell : entry.getValue()) {
                List<TableCell> rowCells = byRow.getOrDefault(labelCell.row(), List.of()).stream()
                        .filter(cell -> cell.column() > labelCell.column())
                        .sorted(Comparator.comparingInt(TableCell::column))
                        .toList();
                if (!rowCells.isEmpty()) {
                    evaluateContextCandidate(findings, labelCell, List.of(rowCells.get(0)), detectionSource);
                    evaluateContextCandidate(findings, labelCell, rowCells.stream().limit(3).toList(), detectionSource);
                    evaluateContextCandidate(findings, labelCell, rowCells, detectionSource);
                }

                List<TableCell> columnCells = byColumn.getOrDefault(labelCell.column(), List.of()).stream()
                        .filter(cell -> cell.row() > labelCell.row())
                        .sorted(Comparator.comparingInt(TableCell::row))
                        .toList();
                if (!columnCells.isEmpty()) {
                    evaluateContextCandidate(findings, labelCell, List.of(columnCells.get(0)), detectionSource);
                    evaluateContextCandidate(findings, labelCell, columnCells.stream().limit(2).toList(), detectionSource);
                }
            }
        }
    }

    private void evaluateContextCandidate(List<PiiFinding> findings,
                                          TableCell labelCell,
                                          List<TableCell> valueCells,
                                          String detectionSource) {
        if (valueCells == null || valueCells.isEmpty()) {
            return;
        }
        String valueText = valueCells.stream()
                .map(TableCell::text)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" "))
                .trim();
        if (!StringUtils.hasText(valueText)) {
            return;
        }

        PiiBoundingBox union = union(valueCells.stream()
                .map(TableCell::boundingBox)
                .toList());
        for (PiiTextMatch match : PiiPatternMatcher.findMatchesInContext(labelCell.text(), valueText)) {
            findings.add(new PiiFinding(
                    match.type(),
                    match.label(),
                    match.originalText(),
                    match.maskedText(),
                    labelCell.pageNumber(),
                    union,
                    detectionSource
            ));
        }
    }

    private List<PiiFinding> detectPdfRegionTiles(Path pdfFile,
                                                  Path workingDirectory,
                                                  JsonNode documentJson,
                                                  int existingFindingCount,
                                                  long startTime) throws IOException, InterruptedException {
        if (existingFindingCount >= 6) {
            return List.of();
        }

        List<CandidateRegion> candidateRegions = extractCandidateRegions(documentJson);
        if (candidateRegions.isEmpty()) {
            return List.of();
        }

        Path tileDirectory = workingDirectory.resolve("hybrid-direct-tiles");
        Files.createDirectories(tileDirectory);

        List<PiiFinding> findings = new ArrayList<>();
        int tileCounter = 0;
        try (PDDocument document = Loader.loadPDF(pdfFile.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            Map<Integer, BufferedImage> renderedPages = new LinkedHashMap<>();
            Map<Integer, Double> pageHeights = loadPageHeights(document);

            for (CandidateRegion region : candidateRegions.stream().limit(3).toList()) {
                BufferedImage pageImage = renderedPages.computeIfAbsent(region.pageNumber(), pageNumber ->
                        renderPage(renderer, pageNumber - 1));
                double pageHeight = pageHeights.getOrDefault(region.pageNumber(), (double) pageImage.getHeight());
                for (CropWindow cropWindow : buildFocusWindows(region)) {
                    if (tileCounter >= MAX_TILE_CALLS) {
                        log.info("[PII-HYBRID] Tile limit reached ({} tiles)", tileCounter);
                        return findings;
                    }
                    if (System.currentTimeMillis() - startTime >= MAX_DETECT_ELAPSED_MILLIS) {
                        log.warn("[PII-HYBRID] Aborting PDF tiles – elapsed time exceeded {}ms after {} tiles", MAX_DETECT_ELAPSED_MILLIS, tileCounter);
                        return findings;
                    }
                    BufferedImage tileImage = crop(pageImage, cropWindow);
                    if (tileImage == null) {
                        continue;
                    }
                    ScaledImage scaledImage = upscaleIfNeeded(tileImage);
                    Path tilePath = tileDirectory.resolve("page-" + region.pageNumber() + "-tile-" + (++tileCounter) + ".png");
                    ImageIO.write(scaledImage.image(), "PNG", tilePath.toFile());

                    JsonNode tileRoot = convert(tilePath, "image/png");
                    JsonNode tileJson = tileRoot.path("document").path("json_content");
                    findings = new ArrayList<>(PiiFindingMergeSupport.mergeFindings(
                            findings,
                            remapFindings(
                                    extractFindings(tileJson, CoordinateSpace.IMAGE),
                                    cropWindow,
                                    scaledImage.scale(),
                                    pageHeight,
                                    region.pageNumber(),
                                    CoordinateSpace.PDF
                            )
                    ));
                }
            }
        }
        return findings;
    }

    private List<PiiFinding> detectImageRegionTiles(Path imageFile,
                                                    String contentType,
                                                    Path workingDirectory,
                                                    JsonNode documentJson,
                                                    int existingFindingCount,
                                                    long startTime) throws IOException, InterruptedException {
        if (existingFindingCount >= 6) {
            return List.of();
        }

        List<CandidateRegion> candidateRegions = extractCandidateRegions(documentJson);
        if (candidateRegions.isEmpty()) {
            return List.of();
        }

        Path tileDirectory = workingDirectory.resolve("hybrid-direct-image-tiles");
        Files.createDirectories(tileDirectory);
        BufferedImage image = ImagePdfSupport.readImage(imageFile);
        int tileCounter = 0;
        List<PiiFinding> findings = new ArrayList<>();

        for (CandidateRegion region : candidateRegions.stream().limit(3).toList()) {
            for (CropWindow cropWindow : buildFocusWindows(region)) {
                if (tileCounter >= MAX_TILE_CALLS) {
                    log.info("[PII-HYBRID] Image tile limit reached ({} tiles)", tileCounter);
                    return findings;
                }
                if (System.currentTimeMillis() - startTime >= MAX_DETECT_ELAPSED_MILLIS) {
                    log.warn("[PII-HYBRID] Aborting image tiles – elapsed time exceeded {}ms after {} tiles", MAX_DETECT_ELAPSED_MILLIS, tileCounter);
                    return findings;
                }
                BufferedImage tileImage = crop(image, cropWindow);
                if (tileImage == null) {
                    continue;
                }
                ScaledImage scaledImage = upscaleIfNeeded(tileImage);
                Path tilePath = tileDirectory.resolve("image-tile-" + (++tileCounter) + ".png");
                ImageIO.write(scaledImage.image(), "PNG", tilePath.toFile());

                JsonNode tileRoot = convert(tilePath, StringUtils.hasText(contentType) ? contentType : "image/png");
                JsonNode tileJson = tileRoot.path("document").path("json_content");
                findings = new ArrayList<>(PiiFindingMergeSupport.mergeFindings(
                        findings,
                        remapFindings(
                                extractFindings(tileJson, CoordinateSpace.IMAGE),
                                cropWindow,
                                scaledImage.scale(),
                                image.getHeight(),
                                1,
                                CoordinateSpace.IMAGE
                        )
                ));
            }
        }
        return findings;
    }

    private BufferedImage renderPage(PDFRenderer renderer, int pageIndex) {
        try {
            return renderer.renderImageWithDPI(pageIndex, TILE_RENDER_DPI, ImageType.RGB);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to render PDF page for hybrid tile fallback", ex);
        }
    }

    private Map<Integer, Double> loadPageHeights(PDDocument document) {
        Map<Integer, Double> heights = new LinkedHashMap<>();
        for (int index = 0; index < document.getNumberOfPages(); index++) {
            PDPage page = document.getPage(index);
            PDRectangle box = page.getCropBox();
            if (box == null || box.getHeight() <= 0) {
                box = page.getMediaBox();
            }
            heights.put(index + 1, (double) box.getHeight());
        }
        return heights;
    }

    private List<CandidateRegion> extractCandidateRegions(JsonNode documentJson) {
        Map<Integer, PageSize> pageSizes = loadPageSizes(documentJson.path("pages"));
        List<CandidateRegion> regions = new ArrayList<>();

        for (JsonNode tableNode : documentJson.path("tables")) {
            JsonNode prov = tableNode.path("prov").path(0);
            int pageNumber = prov.path("page_no").asInt(1);
            PageSize pageSize = pageSizes.get(pageNumber);
            if (pageSize == null) {
                continue;
            }
            CandidateRegion region = topLeftRegion("table", pageNumber, prov.path("bbox"), pageSize.height());
            if (region != null && region.area() >= pageSize.area() * 0.05d) {
                regions.add(region);
            }
        }

        for (JsonNode pictureNode : documentJson.path("pictures")) {
            JsonNode prov = pictureNode.path("prov").path(0);
            int pageNumber = prov.path("page_no").asInt(1);
            PageSize pageSize = pageSizes.get(pageNumber);
            if (pageSize == null) {
                continue;
            }
            CandidateRegion region = topLeftRegion("picture", pageNumber, prov.path("bbox"), pageSize.height());
            if (region != null && region.area() >= pageSize.area() * 0.10d) {
                regions.add(region);
            }
        }

        for (Map.Entry<Integer, PageSize> entry : pageSizes.entrySet()) {
            PageSize pageSize = entry.getValue();
            regions.add(new CandidateRegion(
                    "default-focus",
                    entry.getKey(),
                    pageSize.width() * 0.06d,
                    pageSize.height() * 0.16d,
                    pageSize.width() * 0.88d,
                    pageSize.height() * 0.70d
            ));
            regions.add(new CandidateRegion(
                    "full-page",
                    entry.getKey(),
                    0d,
                    0d,
                    pageSize.width(),
                    pageSize.height()
            ));
        }

        return regions.stream()
                .filter(region -> region.width() >= 200d && region.height() >= 100d)
                .sorted(Comparator.comparingInt(this::candidatePriority)
                        .thenComparing((CandidateRegion region) -> region.area(), Comparator.reverseOrder()))
                .distinct()
                .toList();
    }

    private int candidatePriority(CandidateRegion region) {
        return switch (region.source()) {
            case "table" -> 0;
            case "picture" -> 1;
            case "default-focus" -> 2;
            case "full-page" -> 3;
            default -> 4;
        };
    }

    private Map<Integer, PageSize> loadPageSizes(JsonNode pagesNode) {
        Map<Integer, PageSize> pageSizes = new LinkedHashMap<>();
        pagesNode.fields().forEachRemaining(entry -> {
            int pageNumber = Integer.parseInt(entry.getKey());
            double width = entry.getValue().path("size").path("width").asDouble();
            double height = entry.getValue().path("size").path("height").asDouble();
            pageSizes.put(pageNumber, new PageSize(width, height));
        });
        return pageSizes;
    }

    private CandidateRegion topLeftRegion(String source, int pageNumber, JsonNode bboxNode, double pageHeight) {
        if (bboxNode == null || bboxNode.isMissingNode()) {
            return null;
        }
        PiiBoundingBox bbox = toBoundingBox(bboxNode, pageHeight, CoordinateSpace.IMAGE);
        return new CandidateRegion(source, pageNumber, bbox.x(), bbox.y(), bbox.width(), bbox.height());
    }

    private List<CropWindow> buildFocusWindows(CandidateRegion region) {
        List<CropWindow> windows = new ArrayList<>();
        windows.add(new CropWindow(region.pageNumber(), region.x(), region.y(), region.width(), region.height(), region.source() + "-full"));

        if (region.width() >= 360d) {
            windows.add(fractionalWindow(region, 0d, 0d, 0.52d, 1d, region.source() + "-left"));
            windows.add(fractionalWindow(region, 0.18d, 0d, 0.64d, 1d, region.source() + "-center"));
            windows.add(fractionalWindow(region, 0.48d, 0d, 0.52d, 1d, region.source() + "-right"));
        }
        if (region.height() >= 240d) {
            windows.add(fractionalWindow(region, 0d, 0d, 1d, 0.56d, region.source() + "-top"));
            windows.add(fractionalWindow(region, 0d, 0.36d, 1d, 0.64d, region.source() + "-bottom"));
            windows.addAll(splitBands(new CropWindow(region.pageNumber(), region.x(), region.y(), region.width(), region.height(), region.source() + "-bands"),
                    TILE_BAND_COUNT,
                    0.16d));
        }
        if (region.width() >= 540d && region.height() >= 260d) {
            windows.addAll(overlapGrid(region, 3, 2, 0.18d));
        }
        return windows.stream()
                .filter(window -> window.width() >= 180d && window.height() >= 80d)
                .distinct()
                .toList();
    }

    private CropWindow fractionalWindow(CandidateRegion region,
                                        double xRatio,
                                        double yRatio,
                                        double widthRatio,
                                        double heightRatio,
                                        String name) {
        return new CropWindow(
                region.pageNumber(),
                region.x() + region.width() * xRatio,
                region.y() + region.height() * yRatio,
                region.width() * widthRatio,
                region.height() * heightRatio,
                name
        );
    }

    private List<CropWindow> splitBands(CropWindow window, int bandCount, double overlapRatio) {
        List<CropWindow> windows = new ArrayList<>();
        double bandHeight = window.height() / bandCount;
        for (int index = 0; index < bandCount; index++) {
            double overlap = bandHeight * overlapRatio;
            double top = window.y() + Math.max(0d, index * bandHeight - overlap);
            double bottom = window.y() + Math.min(window.height(), (index + 1) * bandHeight + overlap);
            windows.add(new CropWindow(
                    window.pageNumber(),
                    window.x(),
                    top,
                    window.width(),
                    Math.max(0d, bottom - top),
                    window.name() + "-band-" + (index + 1)
            ));
        }
        return windows;
    }

    private List<CropWindow> overlapGrid(CandidateRegion region, int columns, int rows, double overlapRatio) {
        List<CropWindow> windows = new ArrayList<>();
        double baseWidth = region.width() / columns;
        double baseHeight = region.height() / rows;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double overlapWidth = baseWidth * overlapRatio;
                double overlapHeight = baseHeight * overlapRatio;
                double x = region.x() + Math.max(0d, column * baseWidth - overlapWidth);
                double y = region.y() + Math.max(0d, row * baseHeight - overlapHeight);
                double right = region.x() + Math.min(region.width(), (column + 1) * baseWidth + overlapWidth);
                double bottom = region.y() + Math.min(region.height(), (row + 1) * baseHeight + overlapHeight);
                windows.add(new CropWindow(
                        region.pageNumber(),
                        x,
                        y,
                        Math.max(0d, right - x),
                        Math.max(0d, bottom - y),
                        region.source() + "-grid-" + (row + 1) + "-" + (column + 1)
                ));
            }
        }
        return windows;
    }

    private BufferedImage crop(BufferedImage pageImage, CropWindow cropWindow) {
        int left = (int) Math.max(0, Math.floor(cropWindow.x()));
        int top = (int) Math.max(0, Math.floor(cropWindow.y()));
        int width = (int) Math.min(pageImage.getWidth() - left, Math.ceil(cropWindow.width()));
        int height = (int) Math.min(pageImage.getHeight() - top, Math.ceil(cropWindow.height()));
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage subImage = pageImage.getSubimage(left, top, width, height);
        BufferedImage copy = new BufferedImage(subImage.getWidth(), subImage.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(subImage, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private ScaledImage upscaleIfNeeded(BufferedImage source) {
        if (source.getWidth() >= TILE_TARGET_WIDTH) {
            return new ScaledImage(source, 1d);
        }
        double scale = Math.min(4d, Math.max(1.5d, TILE_TARGET_WIDTH / (double) source.getWidth()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return new ScaledImage(scaled, scale);
    }

    private List<PiiFinding> remapFindings(List<PiiFinding> tileFindings,
                                           CropWindow cropWindow,
                                           double scale,
                                           double pageHeight,
                                           int pageNumber,
                                           CoordinateSpace coordinateSpace) {
        if (coordinateSpace == CoordinateSpace.PDF) {
            double tileBottom = pageHeight - (cropWindow.y() + cropWindow.height());
            return tileFindings.stream()
                    .map(finding -> new PiiFinding(
                            finding.type(),
                            finding.label(),
                            finding.originalText(),
                            finding.maskedText(),
                            pageNumber,
                            new PiiBoundingBox(
                                    cropWindow.x() + (finding.boundingBox().x() / scale),
                                    tileBottom + (finding.boundingBox().y() / scale),
                                    finding.boundingBox().width() / scale,
                                    finding.boundingBox().height() / scale
                            ),
                            finding.detectionSource() + "-tile"
                    ))
                    .toList();
        }
        return tileFindings.stream()
                .map(finding -> new PiiFinding(
                        finding.type(),
                        finding.label(),
                        finding.originalText(),
                        finding.maskedText(),
                        pageNumber,
                        new PiiBoundingBox(
                                cropWindow.x() + (finding.boundingBox().x() / scale),
                                cropWindow.y() + (finding.boundingBox().y() / scale),
                                finding.boundingBox().width() / scale,
                                finding.boundingBox().height() / scale
                        ),
                        finding.detectionSource() + "-tile"
                ))
                .toList();
    }

    private void addTextNodeFindings(List<PiiFinding> findings,
                                     String text,
                                     JsonNode provNodes,
                                     Map<Integer, Double> pageHeights,
                                     String detectionSource,
                                     CoordinateSpace coordinateSpace) {
        if (!StringUtils.hasText(text) || !provNodes.isArray() || provNodes.isEmpty()) {
            return;
        }
        JsonNode prov = provNodes.get(0);
        int pageNumber = prov.path("page_no").asInt(1);
        double pageHeight = pageHeights.getOrDefault(pageNumber, 0d);
        PiiBoundingBox bbox = toBoundingBox(prov.path("bbox"), pageHeight, coordinateSpace);
        addTextFindings(findings, text, pageNumber, bbox, detectionSource);
    }

    private void addTextFindings(List<PiiFinding> findings,
                                 String text,
                                 int pageNumber,
                                 PiiBoundingBox bbox,
                                 String detectionSource) {
        List<PiiTextMatch> matches = new ArrayList<>(PiiPatternMatcher.findMatches(text));
        if (matches.isEmpty()) {
            addStandaloneHeuristics(matches, text);
        }
        for (PiiTextMatch match : matches) {
            findings.add(new PiiFinding(
                    match.type(),
                    match.label(),
                    match.originalText(),
                    match.maskedText(),
                    pageNumber,
                    bbox,
                    detectionSource
            ));
        }
    }

    private void addStandaloneHeuristics(List<PiiTextMatch> matches, String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.matches(".*\\d{2,}.*") && looksLikeAddress(normalized)) {
            matches.add(new PiiTextMatch(
                    PiiType.STREET_ADDRESS,
                    "주소",
                    normalized,
                    PiiMaskingRules.mask(PiiType.STREET_ADDRESS, normalized),
                    0,
                    normalized.length()
            ));
            return;
        }
        if (normalized.matches("^[가-힣]{2,4}$")) {
            matches.add(new PiiTextMatch(
                    PiiType.PERSON_NAME,
                    "이름",
                    normalized,
                    PiiMaskingRules.mask(PiiType.PERSON_NAME, normalized),
                    0,
                    normalized.length()
            ));
        }
    }

    private boolean looksLikeAddress(String value) {
        return value.length() >= 6
                && value.matches(".*\\d+.*")
                && (value.contains("시 ") || value.contains("도 ") || value.contains("구 ") || value.contains("군 ")
                || value.contains("로 ") || value.contains("길 ") || value.contains("번길 "));
    }

    private Map<Integer, Double> pageHeights(JsonNode pagesNode) {
        LinkedHashMap<Integer, Double> pageHeights = new LinkedHashMap<>();
        pagesNode.fields().forEachRemaining(entry -> {
            int pageNumber = Integer.parseInt(entry.getKey());
            pageHeights.put(pageNumber, entry.getValue().path("size").path("height").asDouble());
        });
        return pageHeights;
    }

    private PiiBoundingBox toBoundingBox(JsonNode bboxNode, double pageHeight, CoordinateSpace coordinateSpace) {
        if (bboxNode == null || bboxNode.isMissingNode()) {
            return new PiiBoundingBox(0d, 0d, 0d, 0d);
        }

        double left = bboxNode.path("l").asDouble();
        double right = bboxNode.path("r").asDouble();
        double topValue = bboxNode.path("t").asDouble();
        double bottomValue = bboxNode.path("b").asDouble();
        String origin = bboxNode.path("coord_origin").asText("TOPLEFT");

        double width = Math.max(0d, right - left);
        double height = Math.abs(topValue - bottomValue);
        if (coordinateSpace == CoordinateSpace.PDF) {
            double y = "BOTTOMLEFT".equalsIgnoreCase(origin)
                    ? Math.max(0d, Math.min(topValue, bottomValue))
                    : Math.max(0d, pageHeight - Math.max(topValue, bottomValue));
            return new PiiBoundingBox(left, y, width, Math.max(0d, height));
        }

        double top = "BOTTOMLEFT".equalsIgnoreCase(origin)
                ? Math.max(0d, pageHeight - Math.max(topValue, bottomValue))
                : Math.min(topValue, bottomValue);
        return new PiiBoundingBox(left, top, width, Math.max(0d, height));
    }

    private PiiBoundingBox union(List<PiiBoundingBox> boxes) {
        double minX = boxes.stream().mapToDouble(PiiBoundingBox::x).min().orElse(0d);
        double minY = boxes.stream().mapToDouble(PiiBoundingBox::y).min().orElse(0d);
        double maxX = boxes.stream().mapToDouble(box -> box.x() + box.width()).max().orElse(minX);
        double maxY = boxes.stream().mapToDouble(box -> box.y() + box.height()).max().orElse(minY);
        return new PiiBoundingBox(minX, minY, Math.max(0d, maxX - minX), Math.max(0d, maxY - minY));
    }

    private String probeContentType(Path file) {
        try {
            return Files.probeContentType(file);
        } catch (IOException ex) {
            return "image/png";
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private enum CoordinateSpace {
        PDF,
        IMAGE
    }

    private record CandidateRegion(
            String source,
            int pageNumber,
            double x,
            double y,
            double width,
            double height
    ) {
        double area() {
            return Math.max(0d, width) * Math.max(0d, height);
        }
    }

    private record CropWindow(
            int pageNumber,
            double x,
            double y,
            double width,
            double height,
            String name
    ) {
    }

    private record PageSize(
            double width,
            double height
    ) {
        double area() {
            return Math.max(0d, width) * Math.max(0d, height);
        }
    }

    private record ScaledImage(
            BufferedImage image,
            double scale
    ) {
    }

    private record TableCell(
            int pageNumber,
            int row,
            int column,
            String text,
            PiiBoundingBox boundingBox
    ) {
    }
}

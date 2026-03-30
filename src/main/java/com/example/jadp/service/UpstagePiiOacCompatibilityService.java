package com.example.jadp.service;

import com.example.jadp.dto.UpstagePiiOacResponse;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiMaskingResult;
import com.example.jadp.support.ImagePdfSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class UpstagePiiOacCompatibilityService {

    private final PiiDocumentService piiDocumentService;

    public UpstagePiiOacCompatibilityService(PiiDocumentService piiDocumentService) {
        this.piiDocumentService = piiDocumentService;
    }

    public UpstagePiiOacResponse detect(MultipartFile file, boolean wrapImageAsPdf) {
        PiiDetectionResult result = piiDocumentService.detect(file, wrapImageAsPdf);
        return toResponse(result, null);
    }

    public UpstagePiiOacResponse mask(MultipartFile file, boolean wrapImageAsPdf) {
        PiiMaskingResult result = piiDocumentService.mask(file, wrapImageAsPdf);
        GeneratedArtifact artifact = result.maskedArtifact();
        UpstagePiiOacResponse.MaskedDocument maskedDocument = new UpstagePiiOacResponse.MaskedDocument(
                artifact.id(),
                artifact.filename(),
                artifact.contentType(),
                fileDownloadUrl(artifact.id())
        );
        return toResponse(result.detectionResult(), maskedDocument);
    }

    private UpstagePiiOacResponse toResponse(PiiDetectionResult result,
                                             UpstagePiiOacResponse.MaskedDocument maskedDocument) {
        Map<Integer, UpstagePiiOacResponse.Page> pages = loadPages(result.sourceFile(), result.mediaType());
        List<UpstagePiiOacResponse.Item> items = result.findings().stream()
                .map(finding -> toItem(finding, result.mediaType(), pages.get(finding.pageNumber())))
                .toList();
        return new UpstagePiiOacResponse(
                "1.0",
                "oac",
                "jadp-pii-masker-oac",
                new UpstagePiiOacResponse.Metadata(
                        result.documentId().toString(),
                        result.originalFilename(),
                        result.contentType(),
                        result.mediaType(),
                        result.pageCount(),
                        pages.values().stream().toList()
                ),
                items,
                maskedDocument
        );
    }

    private UpstagePiiOacResponse.Item toItem(PiiFinding finding,
                                              String mediaType,
                                              UpstagePiiOacResponse.Page page) {
        return new UpstagePiiOacResponse.Item(
                normalizeKey(finding.type().name()),
                finding.type().name(),
                finding.label(),
                finding.originalText(),
                finding.maskedText(),
                finding.detectionSource(),
                List.of(new UpstagePiiOacResponse.BoundingBox(
                        finding.pageNumber(),
                        toVertices(finding, mediaType, page)
                ))
        );
    }

    private List<UpstagePiiOacResponse.Vertex> toVertices(PiiFinding finding,
                                                          String mediaType,
                                                          UpstagePiiOacResponse.Page page) {
        double left = round2(finding.boundingBox().x());
        double right = round2(finding.boundingBox().x() + finding.boundingBox().width());
        double top;
        double bottom;
        if ("pdf".equals(mediaType)) {
            top = round2(page.height() - (finding.boundingBox().y() + finding.boundingBox().height()));
            bottom = round2(page.height() - finding.boundingBox().y());
        } else {
            top = round2(finding.boundingBox().y());
            bottom = round2(finding.boundingBox().y() + finding.boundingBox().height());
        }
        return List.of(
                new UpstagePiiOacResponse.Vertex(left, top),
                new UpstagePiiOacResponse.Vertex(right, top),
                new UpstagePiiOacResponse.Vertex(right, bottom),
                new UpstagePiiOacResponse.Vertex(left, bottom)
        );
    }

    private Map<Integer, UpstagePiiOacResponse.Page> loadPages(Path sourceFile, String mediaType) {
        try {
            if ("pdf".equals(mediaType)) {
                return loadPdfPages(sourceFile);
            }
            return loadImagePage(sourceFile);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load page metadata for OAC response", ex);
        }
    }

    private Map<Integer, UpstagePiiOacResponse.Page> loadPdfPages(Path sourceFile) throws IOException {
        Map<Integer, UpstagePiiOacResponse.Page> pages = new LinkedHashMap<>();
        try (PDDocument document = Loader.loadPDF(sourceFile.toFile())) {
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                PDRectangle box = document.getPage(index).getCropBox();
                if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
                    box = document.getPage(index).getMediaBox();
                }
                pages.put(index + 1, new UpstagePiiOacResponse.Page(
                        index + 1,
                        round2(box.getWidth()),
                        round2(box.getHeight())
                ));
            }
        }
        return pages;
    }

    private Map<Integer, UpstagePiiOacResponse.Page> loadImagePage(Path sourceFile) throws IOException {
        ImagePdfSupport.ImageDimensions dimensions = ImagePdfSupport.dimensions(sourceFile);
        return Map.of(1, new UpstagePiiOacResponse.Page(1, dimensions.width(), dimensions.height()));
    }

    private String normalizeKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private String fileDownloadUrl(String artifactId) {
        try {
            return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/api/v1/pii/files/{fileId}")
                    .buildAndExpand(artifactId)
                    .toUriString();
        } catch (IllegalStateException ignored) {
            return "/api/v1/pii/files/" + artifactId;
        }
    }
}

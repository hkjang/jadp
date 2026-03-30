package com.example.jadp.service;

import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.support.PiiFindingMergeSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class PdfImageAwarePiiDetector {

    private static final float RENDER_DPI = 200f;

    private final PdfStructuredPiiDetector pdfStructuredPiiDetector;
    private final HybridOptionsResolver hybridOptionsResolver;
    private final HybridDoclingDirectPiiDetector hybridDoclingDirectPiiDetector;
    private final VllmVisionPiiDetector vllmVisionPiiDetector;

    public PdfImageAwarePiiDetector(PdfStructuredPiiDetector pdfStructuredPiiDetector,
                                    HybridOptionsResolver hybridOptionsResolver,
                                    HybridDoclingDirectPiiDetector hybridDoclingDirectPiiDetector,
                                    VllmVisionPiiDetector vllmVisionPiiDetector) {
        this.pdfStructuredPiiDetector = pdfStructuredPiiDetector;
        this.hybridOptionsResolver = hybridOptionsResolver;
        this.hybridDoclingDirectPiiDetector = hybridDoclingDirectPiiDetector;
        this.vllmVisionPiiDetector = vllmVisionPiiDetector;
    }

    public PiiDetectionResult detect(UUID documentId,
                                     String originalFilename,
                                     String contentType,
                                     Path sourceFile,
                                     Path workingDirectory) {
        PiiDetectionResult baseline = pdfStructuredPiiDetector.detect(
                documentId,
                originalFilename,
                contentType,
                sourceFile,
                workingDirectory,
                pdfStructuredPiiDetector.defaultDetectionOptions()
        );

        PageAnalysis analysis = analyzePdf(sourceFile);
        PiiDetectionResult enhanced = baseline;
        if (shouldRunAggressiveHybridPass(baseline, analysis)) {
            PiiDetectionResult aggressive = pdfStructuredPiiDetector.detect(
                    documentId,
                    originalFilename,
                    contentType,
                    sourceFile,
                    workingDirectory,
                    pdfStructuredPiiDetector.aggressiveImageAwareOptions()
            );
            enhanced = PiiFindingMergeSupport.mergeDetectionResult(baseline, aggressive.findings());
        }

        if (shouldRunDirectHybridPass(analysis)) {
            enhanced = PiiFindingMergeSupport.mergeDetectionResult(enhanced, hybridDoclingDirectPiiDetector.detect(sourceFile, workingDirectory));
        }

        if (shouldRunVisionFallback(enhanced, analysis)) {
            List<PiiFinding> fallbackFindings = detectRenderedPageFindings(sourceFile, workingDirectory, analysis);
            enhanced = PiiFindingMergeSupport.mergeDetectionResult(enhanced, fallbackFindings);
        }
        return enhanced;
    }

    private boolean shouldRunAggressiveHybridPass(PiiDetectionResult baseline, PageAnalysis analysis) {
        boolean hybridEnabled = hybridOptionsResolver.properties().isEnabled()
                && hybridOptionsResolver.properties().isAutoApplyToPii();
        return hybridEnabled && (baseline.findings().isEmpty() || !analysis.imageHeavyPages().isEmpty());
    }

    private boolean shouldRunDirectHybridPass(PageAnalysis analysis) {
        return !analysis.imageHeavyPages().isEmpty() && hybridDoclingDirectPiiDetector.isConfigured();
    }

    private boolean shouldRunVisionFallback(PiiDetectionResult detectionResult, PageAnalysis analysis) {
        return vllmVisionPiiDetector.isConfigured()
                && !analysis.imageHeavyPages().isEmpty()
                && analysis.imageHeavyPages().stream()
                .anyMatch(pageNumber -> detectionResult.findings().stream().noneMatch(finding -> finding.pageNumber() == pageNumber));
    }

    private List<PiiFinding> detectRenderedPageFindings(Path sourceFile,
                                                        Path workingDirectory,
                                                        PageAnalysis analysis) {
        Path renderedDir = workingDirectory.resolve("rendered-pages");
        try {
            Files.createDirectories(renderedDir);
            try (PDDocument document = Loader.loadPDF(sourceFile.toFile())) {
                PDFRenderer renderer = new PDFRenderer(document);
                List<PiiFinding> findings = new ArrayList<>();
                for (Integer pageNumber : analysis.imageHeavyPages()) {
                    int pageIndex = pageNumber - 1;
                    PDPage page = document.getPage(pageIndex);
                    PDRectangle box = page.getCropBox();
                    if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
                        box = page.getMediaBox();
                    }
                    double pageWidth = box.getWidth();
                    double pageHeight = box.getHeight();

                    BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
                    Path renderedImage = renderedDir.resolve("page-" + pageNumber + ".png");
                    ImageIO.write(rendered, "PNG", renderedImage.toFile());

                    List<PiiFinding> pageFindings = vllmVisionPiiDetector.detectFindings(
                            renderedImage,
                            "image/png",
                            pageNumber,
                            "pdf-rendered-vision"
                    ).stream()
                            .map(finding -> toPdfCoordinates(finding, rendered.getWidth(), rendered.getHeight(), pageWidth, pageHeight))
                            .toList();
                    findings.addAll(pageFindings);
                }
                return findings;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to render PDF pages for PII fallback", ex);
        }
    }

    private PiiFinding toPdfCoordinates(PiiFinding finding,
                                        int imageWidth,
                                        int imageHeight,
                                        double pageWidth,
                                        double pageHeight) {
        double scaleX = pageWidth / imageWidth;
        double scaleY = pageHeight / imageHeight;
        double pdfX = finding.boundingBox().x() * scaleX;
        double pdfWidth = finding.boundingBox().width() * scaleX;
        double pdfHeight = finding.boundingBox().height() * scaleY;
        double pdfY = pageHeight - ((finding.boundingBox().y() + finding.boundingBox().height()) * scaleY);
        return new PiiFinding(
                finding.type(),
                finding.label(),
                finding.originalText(),
                finding.maskedText(),
                finding.pageNumber(),
                new PiiBoundingBox(pdfX, Math.max(0d, pdfY), pdfWidth, pdfHeight),
                finding.detectionSource()
        );
    }

    private PageAnalysis analyzePdf(Path sourceFile) {
        try (PDDocument document = Loader.loadPDF(sourceFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<Integer> imageHeavyPages = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                int pageNumber = index + 1;
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String text = stripper.getText(document).replaceAll("\\s+", "");
                PDPage page = document.getPage(index);
                if (looksImageHeavy(page.getResources(), text)) {
                    imageHeavyPages.add(pageNumber);
                }
            }
            return new PageAnalysis(document.getNumberOfPages(), List.copyOf(imageHeavyPages));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect PDF structure for PII detection", ex);
        }
    }

    private boolean looksImageHeavy(PDResources resources, String text) throws IOException {
        int imageCount = 0;
        if (resources != null) {
            for (var name : resources.getXObjectNames()) {
                if (resources.isImageXObject(name)) {
                    imageCount++;
                }
            }
        }
        return imageCount > 0 && text.length() < 80;
    }

    private record PageAnalysis(int pageCount, List<Integer> imageHeavyPages) {
    }
}

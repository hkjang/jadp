package com.example.jadp.support;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects whether a PDF document or individual page is image-based (scanned).
 *
 * <p>A page is considered "image-based" when it contains at least one embedded
 * image XObject <em>and</em> yields fewer than {@value #MIN_TEXT_CHARS} non-whitespace
 * characters via PDFBox text extraction.  Such pages require OCR to be processed
 * correctly and should be routed directly to docling.</p>
 */
public final class PdfImageContentDetector {

    private static final Logger log = LoggerFactory.getLogger(PdfImageContentDetector.class);

    /** Minimum extractable non-whitespace characters to consider a page "text-based". */
    static final int MIN_TEXT_CHARS = 30;

    /**
     * Fraction of sampled pages that must be image-based to declare the whole
     * PDF image-based.
     */
    private static final double IMAGE_PAGE_RATIO_THRESHOLD = 0.5;

    /** Maximum number of pages sampled when evaluating the whole document. */
    private static final int MAX_PAGES_TO_SAMPLE = 10;

    private PdfImageContentDetector() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the PDF is primarily image-based (scanned).
     *
     * <p>Samples up to {@value #MAX_PAGES_TO_SAMPLE} pages and returns
     * {@code true} when at least 50 % of the sampled pages are image-based.</p>
     */
    public static boolean isImageBasedPdf(Path pdfPath) {
        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            int total = doc.getNumberOfPages();
            if (total == 0) {
                return false;
            }
            int sample = Math.min(total, MAX_PAGES_TO_SAMPLE);
            int imageCount = 0;
            for (int i = 0; i < sample; i++) {
                if (isImageBasedPage(doc, i)) {
                    imageCount++;
                }
            }
            boolean result = imageCount >= Math.ceil(sample * IMAGE_PAGE_RATIO_THRESHOLD);
            log.debug("[PDF-IMG-DETECT] {}/{} sampled pages image-based → isImageBased={}",
                    imageCount, sample, result);
            return result;
        } catch (IOException ex) {
            log.warn("[PDF-IMG-DETECT] Could not analyse '{}' for image content: {}",
                    pdfPath.getFileName(), ex.getMessage());
            return false;
        }
    }

    /**
     * Returns the 0-based indices of all image-based pages in the given document.
     * The caller is responsible for opening and closing the {@link PDDocument}.
     */
    public static List<Integer> imageBasedPageIndices(PDDocument doc) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            if (isImageBasedPage(doc, i)) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * Returns {@code true} when the page at the given 0-based {@code pageIndex}:
     * <ol>
     *   <li>contains at least one {@link PDImageXObject} in its resource dictionary, and</li>
     *   <li>yields fewer than {@value #MIN_TEXT_CHARS} non-whitespace characters.</li>
     * </ol>
     */
    public static boolean isImageBasedPage(PDDocument doc, int pageIndex) {
        if (!hasImageXObject(doc.getPage(pageIndex))) {
            return false;
        }
        String text = extractPageText(doc, pageIndex);
        boolean imageBased = text.length() < MIN_TEXT_CHARS;
        log.trace("[PDF-IMG-DETECT] page {} – imageXObject=true, textChars={} → imageBased={}",
                pageIndex + 1, text.length(), imageBased);
        return imageBased;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static boolean hasImageXObject(PDPage page) {
        try {
            if (page.getResources() == null) {
                return false;
            }
            for (var name : page.getResources().getXObjectNames()) {
                PDXObject xobj = page.getResources().getXObject(name);
                if (xobj instanceof PDImageXObject) {
                    return true;
                }
            }
        } catch (Exception ex) {
            log.debug("[PDF-IMG-DETECT] XObject inspection failed: {}", ex.getMessage());
        }
        return false;
    }

    private static String extractPageText(PDDocument doc, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return stripper.getText(doc).replaceAll("\\s+", "").trim();
        } catch (IOException ex) {
            log.debug("[PDF-IMG-DETECT] Text extraction failed for page {}: {}", pageIndex + 1, ex.getMessage());
            // Cannot extract text → treat page as image-based
            return "";
        }
    }
}

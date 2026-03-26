package com.example.jadp.service;

import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiFinding;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PdfMaskingService {

    private static final float RENDER_DPI = 200f;
    private static final List<String> MASK_FONT_CANDIDATES = List.of(
            "Malgun Gothic",
            "Apple SD Gothic Neo",
            "NanumGothic",
            "Noto Sans CJK KR",
            "Noto Sans KR",
            Font.SANS_SERIF
    );

    public Path createMaskedPdf(Path sourceFile, List<PiiFinding> findings, Path outputFile) {
        try (PDDocument input = Loader.loadPDF(sourceFile.toFile());
             PDDocument masked = new PDDocument()) {
            PDFRenderer renderer = new PDFRenderer(input);
            Map<Integer, List<PiiFinding>> pageFindings = findings.stream()
                    .collect(Collectors.groupingBy(PiiFinding::pageNumber));

            for (int pageIndex = 0; pageIndex < input.getNumberOfPages(); pageIndex++) {
                PDPage sourcePage = input.getPage(pageIndex);
                PDRectangle mediaBox = sourcePage.getMediaBox();
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, 200, ImageType.RGB);
                Graphics2D graphics = image.createGraphics();
                configureGraphics(graphics);
                applyMasks(graphics, image, mediaBox, pageFindings.getOrDefault(pageIndex + 1, List.of()));
                graphics.dispose();

                PDPage outPage = new PDPage(new PDRectangle(mediaBox.getWidth(), mediaBox.getHeight()));
                masked.addPage(outPage);
                PDImageXObject pdImage = LosslessFactory.createFromImage(masked, image);
                try (PDPageContentStream contentStream = new PDPageContentStream(masked, outPage)) {
                    contentStream.drawImage(pdImage, 0, 0, mediaBox.getWidth(), mediaBox.getHeight());
                }
            }
            masked.save(outputFile.toFile());
            return outputFile;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create masked PDF", ex);
        }
    }

    private void applyMasks(Graphics2D graphics,
                            BufferedImage image,
                            PDRectangle mediaBox,
                            List<PiiFinding> findings) {
        double xScale = image.getWidth() / mediaBox.getWidth();
        double yScale = image.getHeight() / mediaBox.getHeight();
        for (PiiFinding finding : findings) {
            PiiBoundingBox bbox = finding.boundingBox();
            int x = (int) Math.floor(bbox.x() * xScale);
            int y = (int) Math.floor((mediaBox.getHeight() - bbox.y() - bbox.height()) * yScale);
            int width = Math.max(8, (int) Math.ceil(bbox.width() * xScale));
            int height = Math.max(8, (int) Math.ceil(bbox.height() * yScale));

            graphics.setColor(Color.WHITE);
            graphics.fillRect(x, y, width, height);
            graphics.setColor(Color.BLACK);
            Font font = resolveMaskFont(finding.maskedText(), Math.max(10, Math.min(height - 2, 28)));
            String overlayText = overlayText(finding.maskedText(), font);
            int fontSize = computeFontSize(graphics, overlayText, font.getFamily(), width, height);
            font = font.deriveFont(Font.BOLD, fontSize);
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics();
            int baseline = y + Math.max(metrics.getAscent(), Math.min(height - 2, (height + metrics.getAscent() - metrics.getDescent()) / 2));
            graphics.drawString(overlayText, x + 2, baseline);
        }
    }

    private void configureGraphics(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private int computeFontSize(Graphics2D graphics, String maskedText, String fontFamily, int width, int height) {
        int maxSize = Math.max(12, Math.min(height - 2, 28));
        for (int size = maxSize; size >= 10; size--) {
            Font font = new Font(fontFamily, Font.BOLD, size);
            FontMetrics metrics = graphics.getFontMetrics(font);
            if (metrics.stringWidth(maskedText) <= Math.max(12, width - 4) && metrics.getHeight() <= Math.max(12, height)) {
                return size;
            }
        }
        return 10;
    }

    private Font resolveMaskFont(String text, int size) {
        Set<String> availableFamilies = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                .collect(Collectors.toSet());
        for (String family : MASK_FONT_CANDIDATES) {
            if (!availableFamilies.contains(family)) {
                continue;
            }
            Font font = new Font(family, Font.BOLD, size);
            if (font.canDisplayUpTo(text) == -1) {
                return font;
            }
        }
        for (String family : availableFamilies) {
            Font font = new Font(family, Font.BOLD, size);
            if (font.canDisplayUpTo(text) == -1) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, size);
    }

    private String overlayText(String maskedText, Font font) {
        if (font.canDisplayUpTo(maskedText) == -1) {
            return maskedText;
        }
        StringBuilder builder = new StringBuilder(maskedText.length());
        for (char ch : maskedText.toCharArray()) {
            if (Character.isWhitespace(ch) || ch == '-' || ch == '.' || ch == '@' || ch == '*' || ch == '/' || ch == ':') {
                builder.append(ch);
            } else {
                builder.append('*');
            }
        }
        return builder.toString();
    }
}

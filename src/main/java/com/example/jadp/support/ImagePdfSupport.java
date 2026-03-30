package com.example.jadp.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public final class ImagePdfSupport {

    private ImagePdfSupport() {
    }

    public static BufferedImage readImage(InputStream inputStream) throws IOException {
        BufferedImage image = ImageIO.read(inputStream);
        if (image == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "The uploaded image could not be decoded as PNG or JPEG.");
        }
        return image;
    }

    public static BufferedImage readImage(Path imagePath) throws IOException {
        BufferedImage image = ImageIO.read(imagePath.toFile());
        if (image == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "The uploaded image could not be decoded as PNG or JPEG.");
        }
        return image;
    }

    public static void wrapImageInPdf(BufferedImage image, Path outputPdf) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
            document.addPage(page);
            var pdImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.drawImage(pdImage, 0, 0, image.getWidth(), image.getHeight());
            }
            document.save(outputPdf.toFile());
        }
    }

    public static ImageDimensions dimensions(Path imagePath) throws IOException {
        BufferedImage image = readImage(imagePath);
        return new ImageDimensions(image.getWidth(), image.getHeight());
    }

    public record ImageDimensions(int width, int height) {
    }
}

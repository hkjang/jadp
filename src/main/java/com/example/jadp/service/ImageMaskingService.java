package com.example.jadp.service;

import com.example.jadp.model.PiiFinding;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@Service
public class ImageMaskingService {

    public Path createMaskedImage(Path sourceFile, List<PiiFinding> findings, Path outputFile) {
        try {
            BufferedImage image = ImageIO.read(sourceFile.toFile());
            if (image == null) {
                throw new IllegalStateException("Unsupported image format");
            }
            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (PiiFinding finding : findings) {
                int x = (int) Math.floor(finding.boundingBox().x());
                int y = (int) Math.floor(finding.boundingBox().y());
                int width = Math.max(8, (int) Math.ceil(finding.boundingBox().width()));
                int height = Math.max(8, (int) Math.ceil(finding.boundingBox().height()));
                graphics.setColor(Color.WHITE);
                graphics.fillRect(x, y, width, height);
                graphics.setColor(Color.BLACK);
                int fontSize = Math.max(12, Math.min(height - 2, width / Math.max(2, finding.maskedText().length())));
                graphics.setFont(new Font("Malgun Gothic", Font.BOLD, fontSize));
                graphics.drawString(finding.maskedText(), x + 2, y + Math.max(fontSize, height - 4));
            }
            graphics.dispose();
            String fileName = outputFile.getFileName().toString().toLowerCase();
            String format = fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ? "jpg" : "png";
            ImageIO.write(image, format, outputFile.toFile());
            return outputFile;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create masked image", ex);
        }
    }
}

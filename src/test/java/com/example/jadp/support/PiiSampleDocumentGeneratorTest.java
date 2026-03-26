package com.example.jadp.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

class PiiSampleDocumentGeneratorTest {

    @Test
    void generateSyntheticKoreanPiiSamples() throws Exception {
        Path samplesDir = Paths.get("samples", "pii");
        Files.createDirectories(samplesDir);

        List<String> fullLines = List.of(
                "PII 종합 샘플 문서",
                "성명: 홍길동",
                "주민등록번호: 800901-1234567",
                "운전면허번호: 11-24-123456-62",
                "여권번호: M12345678",
                "외국인등록번호: 123456-5123456",
                "휴대폰번호: 010-1234-5678",
                "전화번호: 02-1234-5678",
                "신용카드번호: 4111-1111-1111-1111",
                "계좌번호: 123-45-6789-012",
                "이메일: abcdefg@naver.com",
                "IP주소: 192.168.254.123",
                "주소: 서울 영등포구 국제금융로 10 3층"
        );

        List<String> pageOne = List.of(
                "다중 페이지 PII 샘플 1",
                "대표자: 선우용녀",
                "휴대폰번호: 010 9876 5432",
                "전화번호: 031-555-1234",
                "이메일: test@korea.kr",
                "계좌번호: 987-65-4321-001"
        );

        List<String> pageTwo = List.of(
                "다중 페이지 PII 샘플 2",
                "주소: 부산 해운대구 센텀중앙로 97 A동 1203호",
                "여권번호: S76543210",
                "운전면허번호: 26-11-654321-33",
                "주민등록번호: 900101-2345678",
                "외국인등록번호: 770707-7123456",
                "신용카드번호: 4012-8888-8888-1881",
                "IP주소: 10.20.30.40"
        );

        writeTextPdf(samplesDir.resolve("synthetic-korean-pii-full.pdf"), List.of(fullLines));
        writeTextPdf(samplesDir.resolve("synthetic-korean-pii-multipage.pdf"), List.of(pageOne, pageTwo));
        writePng(samplesDir.resolve("synthetic-korean-pii-full.png"), fullLines);
    }

    private void writeTextPdf(Path targetFile, List<List<String>> pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            Path fontPath = resolveKoreanFont();
            PDType0Font font = PDType0Font.load(document, fontPath.toFile());

            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(font, 14);
                    contentStream.setLeading(24);
                    contentStream.newLineAtOffset(50, 800);
                    for (String line : lines) {
                        contentStream.showText(line);
                        contentStream.newLine();
                    }
                    contentStream.endText();
                }
            }
            Files.deleteIfExists(targetFile);
            document.save(targetFile.toFile());
        }
    }

    private void writePng(Path targetFile, List<String> lines) throws IOException {
        BufferedImage image = new BufferedImage(1600, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(new Font("Malgun Gothic", Font.PLAIN, 32));

        int y = 80;
        for (String line : lines) {
            graphics.drawString(line, 60, y);
            y += 70;
        }
        graphics.dispose();
        Files.deleteIfExists(targetFile);
        ImageIO.write(image, "png", targetFile.toFile());
    }

    private Path resolveKoreanFont() {
        Path windowsFont = Paths.get(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Fonts", "malgun.ttf");
        if (Files.exists(windowsFont)) {
            return windowsFont;
        }
        throw new IllegalStateException("Korean font file not found: " + windowsFont);
    }
}

package com.example.jadp.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@SpringBootTest(properties = {
        "app.storage.base-dir=${jadp.test.storage-dir:var/upstage-pii-oac-e2e-data}",
        "app.opendataloader.hybrid.enabled=true",
        "app.opendataloader.hybrid.backend=docling-fast",
        "app.opendataloader.hybrid.url=${jadp.test.hybrid-url:http://localhost:36002}",
        "app.opendataloader.hybrid.auto-apply-to-pii=true",
        "app.opendataloader.hybrid.prefer-full-mode=${jadp.test.prefer-full-mode:true}",
        "app.vllm-ocr.enabled=false"
})
@AutoConfigureMockMvc
class UpstagePiiOacSampleE2EManual {

    private static final Path SAMPLE_DIR = Path.of("samples", "dp");
    private static final String HYBRID_URL = System.getProperty("jadp.test.hybrid-url", "http://localhost:36002");
    private static final Path ARTIFACT_DIR = Path.of(System.getProperty("jadp.test.artifact-dir", "test-artifacts/upstage-pii-oac"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void generateKoreanReportForSampleImages() throws Exception {
        assertHybridBackendAvailable();
        Files.createDirectories(ARTIFACT_DIR);
        Files.createDirectories(ARTIFACT_DIR.resolve("responses"));
        Files.createDirectories(ARTIFACT_DIR.resolve("masked"));
        Files.createDirectories(ARTIFACT_DIR.resolve("overlays"));

        List<SampleCase> samples = List.of(
                new SampleCase(
                        "threat-matrix",
                        SAMPLE_DIR.resolve("Gemini_Generated_Image_7gowx7gowx7gowx7.png"),
                        List.of("010-1234-5678", "1002-345-678901"),
                        List.of("MOBILE_PHONE_NUMBER", "BANK_ACCOUNT_NUMBER")
                ),
                new SampleCase(
                        "pii-table-basic",
                        SAMPLE_DIR.resolve("Gemini_Generated_Image_bsi03sbsi03sbsi0.png"),
                        List.of("800101-1234567", "서울특별시 강남구 테헤란로 123", "1234-5678-9012-3456"),
                        List.of("RESIDENT_REGISTRATION_NUMBER", "STREET_ADDRESS", "CREDIT_CARD_NUMBER")
                ),
                new SampleCase(
                        "pii-table-extended",
                        SAMPLE_DIR.resolve("Gemini_Generated_Image_kgkynakgkynakgky.png"),
                        List.of("800101-1234567", "김철수", "010-1234-5678", "서울특별시 강남구 테헤란로 123", "kcs123@email.com"),
                        List.of("RESIDENT_REGISTRATION_NUMBER", "PERSON_NAME", "MOBILE_PHONE_NUMBER", "STREET_ADDRESS", "EMAIL_ADDRESS")
                )
        );

        List<ResultRow> results = new ArrayList<>();
        for (SampleCase sample : samples) {
            BufferedImage image = ImageIO.read(sample.path().toFile());
            assertThat(image).isNotNull();

            MockMultipartFile detectDocument = new MockMultipartFile(
                    "document",
                    sample.path().getFileName().toString(),
                    MediaType.IMAGE_PNG_VALUE,
                    Files.readAllBytes(sample.path())
            );
            long detectStartedAt = System.nanoTime();
            MvcResult detectResult = mockMvc.perform(multipart("/v1/pii-masker/oac/detect")
                            .file(detectDocument)
                            .param("wrap_image_as_pdf", "true"))
                    .andReturn();
            long detectElapsed = (System.nanoTime() - detectStartedAt) / 1_000_000L;

            String detectBody = detectResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
            Path detectResponsePath = ARTIFACT_DIR.resolve("responses").resolve(sample.id() + "-detect.json");
            Files.writeString(detectResponsePath, detectBody, StandardCharsets.UTF_8);
            JsonNode detectJson = objectMapper.readTree(detectBody);

            MockMultipartFile maskDocument = new MockMultipartFile(
                    "document",
                    sample.path().getFileName().toString(),
                    MediaType.IMAGE_PNG_VALUE,
                    Files.readAllBytes(sample.path())
            );
            long maskStartedAt = System.nanoTime();
            MvcResult maskResult = mockMvc.perform(multipart("/v1/pii-masker/oac/mask")
                            .file(maskDocument)
                            .param("wrap_image_as_pdf", "true"))
                    .andReturn();
            long maskElapsed = (System.nanoTime() - maskStartedAt) / 1_000_000L;
            String maskBody = maskResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
            Path maskResponsePath = ARTIFACT_DIR.resolve("responses").resolve(sample.id() + "-mask.json");
            Files.writeString(maskResponsePath, maskBody, StandardCharsets.UTF_8);
            JsonNode maskJson = objectMapper.readTree(maskBody);

            String fileId = maskJson.path("maskedDocument").path("fileId").asText("");
            byte[] maskedBytes = new byte[0];
            Path maskedPdfPath = ARTIFACT_DIR.resolve("masked").resolve(sample.id() + "-masked.pdf");
            if (!fileId.isBlank()) {
                MvcResult downloadResult = mockMvc.perform(get("/api/v1/pii/files/{fileId}", fileId)).andReturn();
                maskedBytes = downloadResult.getResponse().getContentAsByteArray();
                Files.write(maskedPdfPath, maskedBytes);
            }

            Path overlayPath = ARTIFACT_DIR.resolve("overlays").resolve(sample.id() + "-overlay.png");
            Files.write(overlayPath, renderOverlay(image, detectJson));

            List<String> detectedValues = detectJson.path("items").isArray()
                    ? toTextList(detectJson.path("items"), "value")
                    : List.of();
            List<String> detectedTypes = detectJson.path("items").isArray()
                    ? toTextList(detectJson.path("items"), "type")
                    : List.of();
            List<String> matchedValues = sample.expectedValues().stream()
                    .filter(expected -> detectedValues.stream().anyMatch(value -> value.contains(expected)))
                    .toList();
            List<String> matchedTypes = sample.expectedTypes().stream()
                    .filter(detectedTypes::contains)
                    .toList();

            Page page = loadPage(detectJson);
            int bboxCount = countBoundingBoxes(detectJson);
            int inBoundsCount = countInBoundsBoundingBoxes(detectJson, page.width(), page.height());

            results.add(new ResultRow(
                    sample.id(),
                    sample.path().getFileName().toString(),
                    image.getWidth(),
                    image.getHeight(),
                    Files.size(sample.path()),
                    detectResult.getResponse().getStatus(),
                    detectElapsed,
                    detectJson.path("items").size(),
                    matchedValues,
                    matchedTypes,
                    bboxCount,
                    inBoundsCount,
                    maskResult.getResponse().getStatus(),
                    maskElapsed,
                    fileId,
                    maskedBytes.length,
                    topItems(detectJson),
                    sample.expectedValues(),
                    sample.expectedTypes(),
                    detectResponsePath.toString().replace('\\', '/'),
                    maskResponsePath.toString().replace('\\', '/'),
                    maskedPdfPath.toString().replace('\\', '/'),
                    overlayPath.toString().replace('\\', '/')
            ));
        }

        Path jsonReport = ARTIFACT_DIR.resolve("upstage-pii-oac-results.json");
        Files.writeString(jsonReport,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results),
                StandardCharsets.UTF_8);

        Path markdownReport = ARTIFACT_DIR.resolve("UPSTAGE_PII_OAC_REPORT.md");
        Files.writeString(markdownReport, buildMarkdownReport(results), StandardCharsets.UTF_8);

        assertThat(results).hasSize(3);
        assertThat(Files.exists(jsonReport)).isTrue();
        assertThat(Files.exists(markdownReport)).isTrue();
    }

    private void assertHybridBackendAvailable() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(HYBRID_URL + "/docs"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding()
        );
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private List<String> toTextList(JsonNode items, String field) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> {
            String value = item.path(field).asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private int countBoundingBoxes(JsonNode detectJson) {
        int count = 0;
        for (JsonNode item : detectJson.path("items")) {
            count += item.path("boundingBoxes").size();
        }
        return count;
    }

    private int countInBoundsBoundingBoxes(JsonNode detectJson, double width, double height) {
        int count = 0;
        for (JsonNode item : detectJson.path("items")) {
            for (JsonNode box : item.path("boundingBoxes")) {
                boolean inBounds = true;
                for (JsonNode vertex : box.path("vertices")) {
                    double x = vertex.path("x").asDouble(-1d);
                    double y = vertex.path("y").asDouble(-1d);
                    if (x < 0 || y < 0 || x > width || y > height) {
                        inBounds = false;
                        break;
                    }
                }
                if (inBounds) {
                    count++;
                }
            }
        }
        return count;
    }

    private Page loadPage(JsonNode detectJson) {
        JsonNode page = detectJson.path("metadata").path("pages").path(0);
        return new Page(page.path("width").asDouble(), page.path("height").asDouble());
    }

    private byte[] renderOverlay(BufferedImage source, JsonNode detectJson) throws Exception {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.setColor(new Color(225, 29, 72));
        graphics.setStroke(new BasicStroke(4f));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));

        for (JsonNode item : detectJson.path("items")) {
            String label = item.path("label").asText(item.path("type").asText(""));
            for (JsonNode box : item.path("boundingBoxes")) {
                JsonNode vertices = box.path("vertices");
                if (!vertices.isArray() || vertices.size() < 4) {
                    continue;
                }
                int left = (int) Math.round(vertices.get(0).path("x").asDouble());
                int top = (int) Math.round(vertices.get(0).path("y").asDouble());
                int right = (int) Math.round(vertices.get(2).path("x").asDouble());
                int bottom = (int) Math.round(vertices.get(2).path("y").asDouble());
                graphics.drawRect(left, top, Math.max(4, right - left), Math.max(4, bottom - top));
                graphics.setColor(new Color(225, 29, 72, 220));
                graphics.fillRect(left, Math.max(0, top - 28), Math.max(60, label.length() * 14), 24);
                graphics.setColor(Color.WHITE);
                graphics.drawString(label, left + 4, Math.max(18, top - 8));
                graphics.setColor(new Color(225, 29, 72));
            }
        }
        graphics.dispose();

        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        ImageIO.write(copy, "PNG", outputStream);
        return outputStream.toByteArray();
    }

    private List<String> topItems(JsonNode detectJson) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : detectJson.path("items")) {
            String type = item.path("type").asText("");
            String value = item.path("value").asText("");
            if (!type.isBlank() && !value.isBlank()) {
                values.add(type + ": " + trim(value, 80));
            }
        }
        return values.stream().distinct().limit(6).toList();
    }

    private String buildMarkdownReport(List<ResultRow> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Upstage PII Masker OAC 호환 E2E 보고서\n\n");
        builder.append("- 생성 시각: ").append(Instant.now()).append("\n");
        builder.append("- 대상 엔드포인트: `/v1/pii-masker/oac/detect`, `/v1/pii-masker/oac/mask`\n");
        builder.append("- 실행 방식: Spring MockMvc + OpenDataLoader Hybrid(`").append(HYBRID_URL).append("`) + PNG 원본 direct 분석 + PNG 자동 PDF 래핑 앙상블\n");
        builder.append("- 테스트 장비 기준: 로컬 GPU 장비에서 실행\n");
        builder.append("- 샘플 폴더: `samples/dp`\n\n");

        builder.append("## 요약\n\n");
        builder.append("| 샘플 | HTTP detect | detect ms | 항목 수 | 기대 값 적중 | 기대 타입 적중 | bbox 정상 | HTTP mask | mask ms | masked bytes |\n");
        builder.append("| --- | ---: | ---: | ---: | --- | --- | --- | ---: | ---: | ---: |\n");
        for (ResultRow result : results) {
            builder.append("| ").append(result.sampleId()).append(" | ")
                    .append(result.detectStatus()).append(" | ")
                    .append(result.detectElapsedMillis()).append(" | ")
                    .append(result.itemCount()).append(" | ")
                    .append(result.matchedValues().size()).append("건 | ")
                    .append(result.matchedTypes().size()).append("건 | ")
                    .append(result.inBoundsBoundingBoxes()).append("/").append(result.boundingBoxCount()).append(" | ")
                    .append(result.maskStatus()).append(" | ")
                    .append(result.maskElapsedMillis()).append(" | ")
                    .append(result.maskedBytes()).append(" |\n");
        }

        int totalDetected = results.stream().mapToInt(ResultRow::itemCount).sum();
        int totalMatchedValues = results.stream().mapToInt(result -> result.matchedValues().size()).sum();
        int totalExpectedValues = results.stream().mapToInt(result -> result.expectedValues().size()).sum();
        int totalMatchedTypes = results.stream().mapToInt(result -> result.matchedTypes().size()).sum();
        int totalExpectedTypes = results.stream().mapToInt(result -> result.expectedTypes().size()).sum();

        builder.append("\n## 집계\n\n");
        builder.append("- 총 탐지 항목 수: ").append(totalDetected).append("건\n");
        builder.append("- 기대 값 적중: ").append(totalMatchedValues).append("/").append(totalExpectedValues).append("건\n");
        builder.append("- 기대 타입 적중: ").append(totalMatchedTypes).append("/").append(totalExpectedTypes).append("건\n");
        builder.append("- 하이브리드 보강 전략: `원본 이미지 direct 분석 -> PDF 래핑 전체 분석 -> 표/그림 영역 재시도 -> 좌/중/우 컬럼 + 상/하 밴드 + 격자 타일 재시도 -> 표 라벨/값 문맥 매칭`\n\n");

        builder.append("\n## 상세 결과\n\n");
        for (ResultRow result : results) {
            builder.append("### ").append(result.sampleId()).append("\n\n");
            builder.append("- 파일: `").append(result.filename()).append("`\n");
            builder.append("- 원본 크기: ").append(result.width()).append("x").append(result.height())
                    .append(", ").append(result.fileSize()).append(" bytes\n");
            builder.append("- Detect: HTTP ").append(result.detectStatus()).append(", ")
                    .append(result.detectElapsedMillis()).append(" ms, 항목 ").append(result.itemCount()).append("건\n");
            builder.append("- 기대 값 목록: ").append(String.join(", ", result.expectedValues())).append("\n");
            builder.append("- 기대 값 적중: ")
                    .append(result.matchedValues().isEmpty() ? "없음" : String.join(", ", result.matchedValues()))
                    .append("\n");
            builder.append("- 기대 타입 목록: ").append(String.join(", ", result.expectedTypes())).append("\n");
            builder.append("- 기대 타입 적중: ")
                    .append(result.matchedTypes().isEmpty() ? "없음" : String.join(", ", result.matchedTypes()))
                    .append("\n");
            builder.append("- bbox 정상 여부: ").append(result.inBoundsBoundingBoxes())
                    .append("/").append(result.boundingBoxCount()).append("가 페이지 범위 내\n");
            builder.append("- Mask: HTTP ").append(result.maskStatus()).append(", ")
                    .append(result.maskElapsedMillis()).append(" ms, 파일 ID `").append(result.maskedFileId()).append("`\n");
            builder.append("- Masked PDF 크기: ").append(result.maskedBytes()).append(" bytes\n");
            builder.append("- 상위 탐지 항목: ")
                    .append(result.topItems().isEmpty() ? "없음" : String.join(" | ", result.topItems()))
                    .append("\n");
            builder.append("- Detect 응답: `").append(result.detectResponsePath()).append("`\n");
            builder.append("- Mask 응답: `").append(result.maskResponsePath()).append("`\n");
            builder.append("- Masked PDF: `").append(result.maskedPdfPath()).append("`\n");
            builder.append("- Overlay 이미지: `").append(result.overlayPath()).append("`\n\n");
        }

        builder.append("## 해석\n\n");
        builder.append("- 이번 경로는 PNG 업로드를 원본 이미지 기준으로 한 번 직접 분석하고, 같은 파일을 1페이지 PDF로 감싼 경로도 함께 분석한 뒤 결과를 병합합니다.\n");
        builder.append("- 응답 bbox는 OAC 스타일의 `boundingBoxes[].vertices[]` 절대 좌표로 내려가며, overlay 이미지로 원본 샘플 위 시각 검증도 함께 남겼습니다.\n");
        builder.append("- 마스킹 API는 동일 탐지 결과를 사용해 masked PDF를 생성하고 `/api/v1/pii/files/{id}` 다운로드 경로까지 제공합니다.\n");
        builder.append("- PDF/이미지형 문서 대응 전략은 `원본 이미지 direct hybrid -> PDF full hybrid -> 표/그림 영역 재시도 -> 좌/중/우 컬럼 + 상/하 밴드 + 3x2 격자 타일 -> 표 라벨/값 문맥 매칭 -> 필요 시 PDF 페이지 이미지 fallback` 순서로 구현되었습니다.\n");
        builder.append("- 한글 이름/주소처럼 OCR 난도가 높은 항목은 샘플 3종 기준으로 일부 미검출이 남아 있었고, 숫자/이메일 중심 개인정보가 상대적으로 안정적으로 잡혔습니다.\n");
        return builder.toString();
    }

    private String trim(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private record Page(double width, double height) {
    }

    private record SampleCase(
            String id,
            Path path,
            List<String> expectedValues,
            List<String> expectedTypes
    ) {
    }

    private record ResultRow(
            String sampleId,
            String filename,
            int width,
            int height,
            long fileSize,
            int detectStatus,
            long detectElapsedMillis,
            int itemCount,
            List<String> matchedValues,
            List<String> matchedTypes,
            int boundingBoxCount,
            int inBoundsBoundingBoxes,
            int maskStatus,
            long maskElapsedMillis,
            String maskedFileId,
            long maskedBytes,
            List<String> topItems,
            List<String> expectedValues,
            List<String> expectedTypes,
            String detectResponsePath,
            String maskResponsePath,
            String maskedPdfPath,
            String overlayPath
    ) {
    }
}

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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

@SpringBootTest(properties = {
        "app.storage.base-dir=var/upstage-parse-sample-e2e-data",
        "app.opendataloader.hybrid.enabled=false"
})
@AutoConfigureMockMvc
class UpstageParseSampleE2EManual {

    private static final Path SAMPLE_DIR = Path.of("samples", "dp");
    private static final Path ARTIFACT_DIR = Path.of("test-artifacts", "upstage-parse-compat");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void generateCompatibilityReportFromSampleImages() throws Exception {
        Files.createDirectories(ARTIFACT_DIR);
        Files.createDirectories(ARTIFACT_DIR.resolve("responses"));

        List<SampleCase> samples = List.of(
                new SampleCase(
                        "threat-matrix",
                        SAMPLE_DIR.resolve("Gemini_Generated_Image_7gowx7gowx7gowx7.png"),
                        List.of("개인정보 보호", "메신저 피싱", "이철수", "010-1234-5678", "user01", "p@ssword123")
                ),
                new SampleCase(
                        "pii-table-basic",
                        SAMPLE_DIR.resolve("Gemini_Generated_Image_bsi03sbsi03sbsi0.png"),
                        List.of("개인정보 보호", "주민등록번호", "800101-1234567", "서울특별시 강남구 테헤란로 123", "1234-5678-9012-3456")
                ),
                new SampleCase(
                        "pii-table-extended",
                        SAMPLE_DIR.resolve("Gemini_Generated_Image_kgkynakgkynakgky.png"),
                        List.of("개인정보 보호", "주민등록번호", "김철수", "010-1234-5678", "kcs123@email.com")
                )
        );

        List<Scenario> scenarios = List.of(
                new Scenario(
                        "default",
                        "Upstage 스타일 최소 요청. document + model + ocr만 사용.",
                        Map.of(
                                "model", "document-parse",
                                "ocr", "force"
                        )
                ),
                new Scenario(
                        "extended-options",
                        "호환 파라미터를 더 붙인 요청. base64_encoding, pages, use_struct_tree, keep_line_breaks, reading_order, table_method 포함.",
                        Map.of(
                                "model", "document-parse",
                                "ocr", "force",
                                "base64_encoding", "[\"table\"]",
                                "pages", "1",
                                "use_struct_tree", "true",
                                "keep_line_breaks", "true",
                                "reading_order", "xycut",
                                "table_method", "default"
                        )
                )
        );

        List<ResultRow> results = new ArrayList<>();
        for (SampleCase sample : samples) {
            assertThat(Files.exists(sample.path())).as("sample exists: " + sample.path()).isTrue();
            BufferedImage image = ImageIO.read(sample.path().toFile());
            assertThat(image).as("image decodes: " + sample.path()).isNotNull();

            for (Scenario scenario : scenarios) {
                MockMultipartFile multipartFile = new MockMultipartFile(
                        "document",
                        sample.path().getFileName().toString(),
                        MediaType.IMAGE_PNG_VALUE,
                        Files.readAllBytes(sample.path())
                );

                MockMultipartHttpServletRequestBuilder request = multipart("/v1/document-digitization").file(multipartFile);
                scenario.params().forEach(request::param);

                long startedAt = System.nanoTime();
                MvcResult mvcResult = mockMvc.perform(request).andReturn();
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

                int status = mvcResult.getResponse().getStatus();
                String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
                Path responseFile = ARTIFACT_DIR.resolve("responses")
                        .resolve(sample.id() + "-" + scenario.id() + ".json");
                Files.writeString(responseFile, body, StandardCharsets.UTF_8);

                JsonNode root = status == 200 && !body.isBlank() ? objectMapper.readTree(body) : null;
                List<String> extracts = extractTopTexts(root);
                Map<String, Integer> categories = countCategories(root);
                String combinedText = combineAllText(root);
                List<String> matchedTerms = sample.expectedTerms().stream()
                        .filter(combinedText::contains)
                        .toList();

                results.add(new ResultRow(
                        sample.id(),
                        sample.path().getFileName().toString(),
                        image.getWidth(),
                        image.getHeight(),
                        Files.size(sample.path()),
                        scenario.id(),
                        scenario.description(),
                        status,
                        elapsedMillis,
                        root == null ? null : root.path("model").asText(""),
                        root == null ? 0 : root.path("usage").path("pages").asInt(0),
                        root == null ? 0 : root.path("elements").size(),
                        root == null ? 0 : root.path("content").path("html").asText("").length(),
                        root == null ? 0 : root.path("content").path("markdown").asText("").length(),
                        root == null ? 0 : root.path("content").path("text").asText("").length(),
                        matchedTerms,
                        sample.expectedTerms(),
                        categories,
                        extracts,
                        responseFile.toString().replace('\\', '/')
                ));
            }
        }

        Path jsonReport = ARTIFACT_DIR.resolve("upstage-parse-compat-results.json");
        Files.writeString(jsonReport,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results),
                StandardCharsets.UTF_8);

        Path markdownReport = ARTIFACT_DIR.resolve("UPSTAGE_PARSE_COMPAT_REPORT.md");
        Files.writeString(markdownReport, buildMarkdownReport(results), StandardCharsets.UTF_8);

        assertThat(results).hasSize(samples.size() * scenarios.size());
        assertThat(results.stream().allMatch(result -> result.status() == 200)).isTrue();
    }

    private List<String> extractTopTexts(JsonNode root) {
        if (root == null || !root.path("elements").isArray()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode element : root.path("elements")) {
            String text = normalize(element.path("content").path("text").asText(""));
            if (!text.isBlank()) {
                texts.add(trimForReport(text, 120));
            }
        }
        return texts.stream().distinct().limit(5).toList();
    }

    private Map<String, Integer> countCategories(JsonNode root) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (root == null || !root.path("elements").isArray()) {
            return counts;
        }
        for (JsonNode element : root.path("elements")) {
            String category = element.path("category").asText("");
            counts.put(category, counts.getOrDefault(category, 0) + 1);
        }
        return counts;
    }

    private String combineAllText(JsonNode root) {
        if (root == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add(root.path("content").path("html").asText(""));
        parts.add(root.path("content").path("markdown").asText(""));
        parts.add(root.path("content").path("text").asText(""));
        if (root.path("elements").isArray()) {
            for (JsonNode element : root.path("elements")) {
                parts.add(element.path("content").path("html").asText(""));
                parts.add(element.path("content").path("markdown").asText(""));
                parts.add(element.path("content").path("text").asText(""));
            }
        }
        return normalize(String.join("\n", parts));
    }

    private String buildMarkdownReport(List<ResultRow> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Upstage Parse 호환성 테스트 보고서\n\n");
        builder.append("- 생성 시각: ").append(Instant.now()).append("\n");
        builder.append("- 대상 엔드포인트: `/v1/document-digitization` (Spring MockMvc 기반 호출)\n");
        builder.append("- 샘플 폴더: `samples/dp`\n");
        builder.append("- 하이브리드 백엔드 사용 여부: 미사용 (`app.opendataloader.hybrid.enabled=false`)\n");
        builder.append("- 참고: PNG 파일은 내부에서 1페이지 PDF로 감싼 뒤 OpenDataLoader 경로로 처리됩니다.\n\n");

        builder.append("## 요약\n\n");
        builder.append("| 샘플 | 시나리오 | HTTP | ms | 요소 수 | HTML 길이 | MD 길이 | Text 길이 | 기대 키워드 적중 |\n");
        builder.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (ResultRow result : results) {
            builder.append("| ")
                    .append(result.sampleId()).append(" | ")
                    .append(result.scenarioId()).append(" | ")
                    .append(result.status()).append(" | ")
                    .append(result.elapsedMillis()).append(" | ")
                    .append(result.elementCount()).append(" | ")
                    .append(result.htmlLength()).append(" | ")
                    .append(result.markdownLength()).append(" | ")
                    .append(result.textLength()).append(" | ")
                    .append(result.matchedTerms().size()).append("/").append(result.expectedTerms().size())
                    .append(" |\n");
        }

        builder.append("\n## 상세 결과\n\n");
        for (ResultRow result : results) {
            builder.append("### ").append(result.sampleId()).append(" / ").append(result.scenarioId()).append("\n\n");
            builder.append("- 파일: `").append(result.filename()).append("`\n");
            builder.append("- 이미지 크기: ").append(result.width()).append("x").append(result.height())
                    .append(", ").append(result.fileSize()).append(" bytes\n");
            builder.append("- HTTP 상태: ").append(result.status()).append("\n");
            builder.append("- 처리 시간: ").append(result.elapsedMillis()).append(" ms\n");
            builder.append("- 응답 모델 값: `").append(result.modelEcho()).append("`\n");
            builder.append("- 페이지 수: ").append(result.pages()).append("\n");
            builder.append("- 추출 요소 수: ").append(result.elementCount()).append("\n");
            builder.append("- 본문 길이: html=").append(result.htmlLength())
                    .append(", markdown=").append(result.markdownLength())
                    .append(", text=").append(result.textLength()).append("\n");
            builder.append("- 기대 키워드 적중: ");
            if (result.matchedTerms().isEmpty()) {
                builder.append("없음");
            } else {
                builder.append(result.matchedTerms().stream().collect(Collectors.joining(", ")));
            }
            builder.append("\n");
            builder.append("- 카테고리 집계: ");
            if (result.categoryCounts().isEmpty()) {
                builder.append("없음");
            } else {
                builder.append(result.categoryCounts().entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining(", ")));
            }
            builder.append("\n");
            builder.append("- 상위 추출 텍스트: ");
            if (result.topExtracts().isEmpty()) {
                builder.append("없음");
            } else {
                builder.append(result.topExtracts().stream().collect(Collectors.joining(" | ")));
            }
            builder.append("\n");
            builder.append("- 원본 응답: `").append(result.responsePath()).append("`\n\n");
        }

        builder.append("## 해석\n\n");
        builder.append("- 현재 호환 레이어는 Upstage 스타일의 입력 및 응답 형식을 맞춰 주지만, 실제 파싱 엔진은 여전히 OpenDataLoader입니다.\n");
        builder.append("- 이번 샘플은 이미지 기반 PNG 문서이므로, 별도 OCR 또는 하이브리드 백엔드 없이 PDF 래핑 경로만 사용할 경우 텍스트 복원 성능이 매우 제한적이었습니다.\n");
        builder.append("- 6개 시나리오 모두 HTTP 200으로 정상 응답했지만, 세 샘플 모두 `figure` 1건만 반환했고 한글 텍스트와 개인정보 예시 값은 추출되지 않았습니다.\n");
        builder.append("- `base64_encoding` 파라미터는 호환성 차원에서 입력은 받지만, 현재 구현은 base64 이미지 payload를 응답에 포함하지 않습니다.\n");
        return builder.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String trimForReport(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private record SampleCase(
            String id,
            Path path,
            List<String> expectedTerms
    ) {
    }

    private record Scenario(
            String id,
            String description,
            Map<String, String> params
    ) {
    }

    private record ResultRow(
            String sampleId,
            String filename,
            int width,
            int height,
            long fileSize,
            String scenarioId,
            String scenarioDescription,
            int status,
            long elapsedMillis,
            String modelEcho,
            int pages,
            int elementCount,
            int htmlLength,
            int markdownLength,
            int textLength,
            List<String> matchedTerms,
            List<String> expectedTerms,
            Map<String, Integer> categoryCounts,
            List<String> topExtracts,
            String responsePath
    ) {
    }
}

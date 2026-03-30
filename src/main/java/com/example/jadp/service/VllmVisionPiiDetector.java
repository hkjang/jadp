package com.example.jadp.service;

import com.example.jadp.config.VllmOcrProperties;
import com.example.jadp.model.PiiBoundingBox;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.model.PiiType;
import com.example.jadp.support.ApiException;
import com.example.jadp.support.PiiMaskingRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class VllmVisionPiiDetector {

    private final VllmOcrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VllmVisionPiiDetector(VllmOcrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public PiiDetectionResult detect(UUID documentId,
                                     String originalFilename,
                                     String contentType,
                                     Path sourceFile) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PNG OCR requires app.vllm-ocr.enabled=true and configured base-url/model.");
        }
        try {
            return new PiiDetectionResult(
                    documentId,
                    originalFilename,
                    contentType,
                    "image",
                    1,
                    sourceFile,
                    detectFindings(sourceFile, contentType, 1, "vllm-vision")
            );
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "vLLM OCR invocation failed: " + ex.getMessage(), ex);
        }
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && StringUtils.hasText(properties.getBaseUrl())
                && StringUtils.hasText(properties.getModel());
    }

    public List<PiiFinding> detectFindings(Path sourceFile,
                                           String contentType,
                                           int pageNumber,
                                           String detectionSource) throws IOException {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PNG OCR requires app.vllm-ocr.enabled=true and configured base-url/model.");
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(sourceFile));
            String mimeType = StringUtils.hasText(contentType) ? contentType : Files.probeContentType(sourceFile);
            if (!StringUtils.hasText(mimeType)) {
                mimeType = "image/png";
            }
            String imageUrl = "data:" + mimeType + ";base64," + base64;
            String prompt = """
                    You are a Korean document PII detector.
                    Detect only the following types when they are clearly visible in the image:
                    RESIDENT_REGISTRATION_NUMBER, DRIVER_LICENSE_NUMBER, PASSPORT_NUMBER,
                    FOREIGNER_REGISTRATION_NUMBER, MOBILE_PHONE_NUMBER, LANDLINE_PHONE_NUMBER,
                    CREDIT_CARD_NUMBER, BANK_ACCOUNT_NUMBER, PERSON_NAME, EMAIL_ADDRESS,
                    IP_ADDRESS, STREET_ADDRESS.
                    Return JSON only with this schema:
                    {
                      "findings":[
                        {
                          "type":"MOBILE_PHONE_NUMBER",
                          "text":"010-1234-5678",
                          "label":"휴대폰번호",
                          "bbox":{"x":120,"y":210,"width":240,"height":32}
                        }
                      ]
                    }
                    Bounding boxes must use original image pixels with top-left origin.
                    If nothing is found, return {"findings":[]}.
                    """;

            String requestBody = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                    .put("model", properties.getModel())
                    .put("temperature", 0)
                    .put("max_tokens", 1200)
                    .set("messages", objectMapper.createArrayNode().add(
                            objectMapper.createObjectNode()
                                    .put("role", "user")
                                    .set("content", objectMapper.createArrayNode()
                                            .add(objectMapper.createObjectNode().put("type", "text").put("text", prompt))
                                            .add(objectMapper.createObjectNode()
                                                    .put("type", "image_url")
                                                    .set("image_url", objectMapper.createObjectNode().put("url", imageUrl)))))));

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            if (StringUtils.hasText(properties.getApiKey())) {
                requestBuilder.header("Authorization", "Bearer " + properties.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "vLLM OCR request failed with status " + response.statusCode() + ": " + response.body());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            String content = extractAssistantContent(responseJson);
            JsonNode parsed = objectMapper.readTree(extractJsonObject(content));
            List<PiiFinding> findings = new ArrayList<>();
            for (JsonNode node : parsed.path("findings")) {
                PiiType type = parseType(node.path("type").asText());
                if (type == null) {
                    continue;
                }
                JsonNode bbox = node.path("bbox");
                findings.add(new PiiFinding(
                        type,
                        node.path("label").asText(type.name()),
                        node.path("text").asText(),
                        PiiMaskingRules.mask(type, node.path("text").asText()),
                        pageNumber,
                        new PiiBoundingBox(
                                bbox.path("x").asDouble(),
                                bbox.path("y").asDouble(),
                                bbox.path("width").asDouble(),
                                bbox.path("height").asDouble()
                        ),
                        detectionSource
                ));
            }
            return findings;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "vLLM OCR invocation failed: " + ex.getMessage(), ex);
        }
    }

    private String extractAssistantContent(JsonNode responseJson) {
        JsonNode contentNode = responseJson.path("choices").path(0).path("message").path("content");
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            contentNode.forEach(node -> builder.append(node.path("text").asText()));
            return builder.toString();
        }
        return responseJson.toPrettyString();
    }

    private String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private PiiType parseType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return PiiType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

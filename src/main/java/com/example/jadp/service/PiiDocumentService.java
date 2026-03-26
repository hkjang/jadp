package com.example.jadp.service;

import com.example.jadp.config.StorageProperties;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiMaskingResult;
import com.example.jadp.support.ApiException;
import com.example.jadp.support.FileNameSanitizer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PiiDocumentService {

    private final PdfStructuredPiiDetector pdfStructuredPiiDetector;
    private final PdfMaskingService pdfMaskingService;
    private final VllmVisionPiiDetector vllmVisionPiiDetector;
    private final ImageMaskingService imageMaskingService;
    private final Path baseDirectory;
    private final Map<String, GeneratedArtifact> artifacts = new ConcurrentHashMap<>();

    public PiiDocumentService(PdfStructuredPiiDetector pdfStructuredPiiDetector,
                              PdfMaskingService pdfMaskingService,
                              VllmVisionPiiDetector vllmVisionPiiDetector,
                              ImageMaskingService imageMaskingService,
                              StorageProperties storageProperties) {
        this.pdfStructuredPiiDetector = pdfStructuredPiiDetector;
        this.pdfMaskingService = pdfMaskingService;
        this.vllmVisionPiiDetector = vllmVisionPiiDetector;
        this.imageMaskingService = imageMaskingService;
        this.baseDirectory = Path.of(storageProperties.getBaseDir()).toAbsolutePath().normalize().resolve("pii-data");
    }

    public PiiDetectionResult detect(MultipartFile file) {
        Workspace workspace = createWorkspace(file);
        return switch (workspace.mediaType()) {
            case "pdf" -> pdfStructuredPiiDetector.detect(
                    workspace.documentId(),
                    workspace.originalFilename(),
                    workspace.contentType(),
                    workspace.sourceFile(),
                    workspace.workingDirectory()
            );
            case "image" -> vllmVisionPiiDetector.detect(
                    workspace.documentId(),
                    workspace.originalFilename(),
                    workspace.contentType(),
                    workspace.sourceFile()
            );
            default -> throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF, PNG, JPG, JPEG are supported.");
        };
    }

    public PiiMaskingResult mask(MultipartFile file) {
        PiiDetectionResult detectionResult = detect(file);
        try {
            Path outputDirectory = detectionResult.sourceFile().getParent().getParent().resolve("output");
            Files.createDirectories(outputDirectory);
            String extension = detectionResult.mediaType().equals("pdf") ? ".pdf" : imageExtension(detectionResult.originalFilename(), detectionResult.contentType());
            String maskedFilename = FileNameSanitizer.sanitize(stripExtension(detectionResult.originalFilename()) + "-masked" + extension);
            Path maskedFile = outputDirectory.resolve(maskedFilename);
            if ("pdf".equals(detectionResult.mediaType())) {
                pdfMaskingService.createMaskedPdf(detectionResult.sourceFile(), detectionResult.findings(), maskedFile);
            } else {
                imageMaskingService.createMaskedImage(detectionResult.sourceFile(), detectionResult.findings(), maskedFile);
            }
            GeneratedArtifact artifact = registerArtifact(maskedFile, detectionResult.mediaType().equals("pdf") ? "pdf" : "image", outputDirectory);
            return new PiiMaskingResult(detectionResult, artifact);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create masked file", ex);
        }
    }

    public GeneratedArtifact getArtifact(String artifactId) {
        GeneratedArtifact artifact = artifacts.get(artifactId);
        if (artifact == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PII artifact not found: " + artifactId);
        }
        return artifact;
    }

    private Workspace createWorkspace(MultipartFile file) {
        validateFile(file);
        UUID documentId = UUID.randomUUID();
        try {
            Path workingDirectory = baseDirectory.resolve(documentId.toString());
            Path inputDirectory = workingDirectory.resolve("input");
            Files.createDirectories(inputDirectory);
            Files.createDirectories(workingDirectory.resolve("output"));

            String originalFilename = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document";
            String safeFilename = FileNameSanitizer.sanitize(originalFilename);
            Path sourceFile = inputDirectory.resolve(safeFilename);
            file.transferTo(sourceFile);

            return new Workspace(
                    documentId,
                    originalFilename,
                    safeFilename,
                    normalizedContentType(file),
                    mediaType(file, originalFilename),
                    sourceFile,
                    workingDirectory
            );
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded file", ex);
        }
    }

    private GeneratedArtifact registerArtifact(Path file, String format, Path outputDirectory) throws IOException {
        String artifactId = UUID.randomUUID().toString();
        GeneratedArtifact artifact = new GeneratedArtifact(
                artifactId,
                format,
                file.getFileName().toString(),
                Files.probeContentType(file) == null ? defaultContentType(format, file) : Files.probeContentType(file),
                Files.size(file),
                outputDirectory.relativize(file).toString().replace('\\', '/'),
                file.toAbsolutePath().normalize()
        );
        artifacts.put(artifactId, artifact);
        return artifact;
    }

    private String defaultContentType(String format, Path file) {
        return switch (format) {
            case "pdf" -> "application/pdf";
            case "image" -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg")
                    || file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpeg")
                    ? "image/jpeg"
                    : "image/png";
            default -> "application/octet-stream";
        };
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A file is required.");
        }
        String mediaType = mediaType(file, file.getOriginalFilename());
        if (mediaType == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF, PNG, JPG, JPEG are supported.");
        }
    }

    private String normalizedContentType(MultipartFile file) {
        if (StringUtils.hasText(file.getContentType())) {
            return file.getContentType();
        }
        return switch (mediaType(file, file.getOriginalFilename())) {
            case "pdf" -> "application/pdf";
            case "image" -> "image/png";
            default -> "application/octet-stream";
        };
    }

    private String mediaType(MultipartFile file, String filename) {
        String contentType = file == null ? null : file.getContentType();
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if ((contentType != null && contentType.equalsIgnoreCase("application/pdf")) || lowerName.endsWith(".pdf")) {
            return "pdf";
        }
        if ((contentType != null && (contentType.equalsIgnoreCase("image/png")
                || contentType.equalsIgnoreCase("image/jpeg")
                || contentType.equalsIgnoreCase("image/jpg")))
                || lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")) {
            return "image";
        }
        return null;
    }

    private String imageExtension(String filename, String contentType) {
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("jpeg"))) {
            return ".jpg";
        }
        return ".png";
    }

    private String stripExtension(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            return filename;
        }
        return filename.substring(0, filename.lastIndexOf('.'));
    }

    private record Workspace(
            UUID documentId,
            String originalFilename,
            String safeFilename,
            String contentType,
            String mediaType,
            Path sourceFile,
            Path workingDirectory
    ) {
    }
}

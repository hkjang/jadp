package com.example.jadp.service;

import com.example.jadp.config.StorageProperties;
import com.example.jadp.model.ConversionJob;
import com.example.jadp.model.GeneratedArtifact;
import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.support.ApiException;
import com.example.jadp.support.FileNameSanitizer;
import com.example.jadp.support.ImagePdfSupport;
import com.example.jadp.support.OptionCatalog;
import com.example.jadp.support.PageRangeValidator;
import com.example.jadp.support.PdfImageContentDetector;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;

@Service
public class PdfJobService {

    private static final Logger log = LoggerFactory.getLogger(PdfJobService.class);

    private final PdfConversionEngine engine;
    private final HybridOptionsResolver hybridOptionsResolver;
    private final SensitiveDataSanitizer sanitizer;
    private final Executor pdfJobExecutor;
    private final Path baseDirectory;
    private final Map<UUID, ConversionJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, GeneratedArtifact> artifacts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<UUID> orderedJobs = new ConcurrentLinkedDeque<>();

    public PdfJobService(PdfConversionEngine engine,
                         HybridOptionsResolver hybridOptionsResolver,
                         SensitiveDataSanitizer sanitizer,
                         @Qualifier("pdfJobExecutor") Executor pdfJobExecutor,
                         StorageProperties storageProperties) {
        this.engine = engine;
        this.hybridOptionsResolver = hybridOptionsResolver;
        this.sanitizer = sanitizer;
        this.pdfJobExecutor = pdfJobExecutor;
        this.baseDirectory = Path.of(storageProperties.getBaseDir()).toAbsolutePath().normalize();
    }

    public ConversionJob submitAsync(MultipartFile file, PdfConversionOptions options) {
        ConversionJob job = createJob(file, normalizeOptions(options));
        pdfJobExecutor.execute(() -> executeJob(job));
        return job;
    }

    public ConversionJob convertSync(MultipartFile file, PdfConversionOptions options) {
        ConversionJob job = createJob(file, normalizeOptions(options));
        executeJob(job);
        return job;
    }

    public ConversionJob getJob(UUID jobId) {
        ConversionJob job = jobs.get(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Job not found: " + jobId);
        }
        return job;
    }

    public List<ConversionJob> listJobs(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(size, 1);
        int fromIndex = safePage * safeSize;
        List<ConversionJob> snapshot = orderedJobs.stream()
                .map(jobs::get)
                .filter(job -> job != null)
                .sorted(Comparator.comparing(ConversionJob::getCreatedAt).reversed())
                .toList();
        if (fromIndex >= snapshot.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + safeSize, snapshot.size());
        return snapshot.subList(fromIndex, toIndex);
    }

    public GeneratedArtifact getArtifact(String artifactId) {
        GeneratedArtifact artifact = artifacts.get(artifactId);
        if (artifact == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not found: " + artifactId);
        }
        return artifact;
    }

    private ConversionJob createJob(MultipartFile file, PdfConversionOptions options) {
        validateFile(file);
        UUID jobId = UUID.randomUUID();
        try {
            Path inputDir = baseDirectory.resolve(jobId.toString()).resolve("input");
            Path outputDir = baseDirectory.resolve(jobId.toString()).resolve("output");
            Files.createDirectories(inputDir);
            Files.createDirectories(outputDir);

            String originalFileName = StringUtils.hasText(file.getOriginalFilename())
                    ? file.getOriginalFilename()
                    : "document.pdf";
            String safeFilename = FileNameSanitizer.sanitize(originalFileName);
            // When uploading an image, it gets wrapped into a single-page PDF – fix the extension
            if ("image".equals(uploadMediaType(file))) {
                safeFilename = safeFilename.replaceAll("(?i)\\.(png|jpe?g)$", ".pdf");
            }
            log.debug("[JOB] originalFilename='{}' → safeFilename='{}'", originalFileName, safeFilename);
            Path uploadPath = inputDir.resolve(safeFilename);
            storeUploadedDocument(file, uploadPath);

            // If the uploaded file is an image-based (scanned) PDF, force docling so OCR is applied.
            if (PdfImageContentDetector.isImageBasedPdf(uploadPath)) {
                log.info("[JOB] Image-based PDF detected: '{}' – forcing hybrid=docling-fast/full for OCR", safeFilename);
                options = forceDoclingOptions(options);
            }

            ConversionJob job = new ConversionJob(jobId, safeFilename, file.getSize(), uploadPath, outputDir, options);
            jobs.put(jobId, job);
            orderedJobs.addFirst(jobId);
            return job;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded file", ex);
        }
    }

    private void executeJob(ConversionJob job) {
        job.markRunning();
        try {
            Files.createDirectories(job.getOutputDirectory());
            PdfConversionOptions options = enrichOptions(job.getOptions(), job.getOutputDirectory());
            engine.convert(job.getUploadPath(), job.getOutputDirectory(), options);
            if (Boolean.TRUE.equals(options.sanitize())) {
                sanitizer.sanitizeRecursively(job.getOutputDirectory());
            }
            List<GeneratedArtifact> generatedArtifacts = indexArtifacts(job.getOutputDirectory());
            generatedArtifacts = rewriteMarkdownImageLinks(generatedArtifacts);
            if (generatedArtifacts.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No output files were generated. Review the requested formats and OpenDataLoader version.");
            }
            job.markSucceeded(generatedArtifacts);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.error("[JOB FAILED] jobId={} file={} – {}", job.getId(), job.getSourceFilename(), message, ex);
            job.markFailed(message);
        }
    }

    private PdfConversionOptions normalizeOptions(PdfConversionOptions options) {
        List<String> formats = OptionCatalog.parseFormats(options.formats());
        if (formats.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "At least one format is required.");
        }
        OptionCatalog.validateAllowedValues("formats", formats, OptionCatalog.FORMATS);
        String readingOrder = OptionCatalog.normalizeOptional(options.readingOrder(), OptionCatalog.DEFAULT_READING_ORDER);
        String tableMethod = OptionCatalog.normalizeOptional(options.tableMethod(), OptionCatalog.DEFAULT_TABLE_METHOD);
        String imageOutput = OptionCatalog.normalizeOptional(options.imageOutput(), OptionCatalog.DEFAULT_IMAGE_OUTPUT);
        String imageFormat = OptionCatalog.normalizeOptional(options.imageFormat(), OptionCatalog.DEFAULT_IMAGE_FORMAT);

        PdfConversionOptions hybridResolved = hybridOptionsResolver.applyDefaults(options, HybridUsage.PDF_REQUEST);
        String hybrid = OptionCatalog.normalizeOptional(hybridResolved.hybrid(), OptionCatalog.DEFAULT_HYBRID);
        String hybridMode = OptionCatalog.normalizeOptional(hybridResolved.hybridMode(), OptionCatalog.DEFAULT_HYBRID_MODE);

        OptionCatalog.validateAllowedValue("readingOrder", readingOrder, OptionCatalog.READING_ORDERS);
        OptionCatalog.validateAllowedValue("tableMethod", tableMethod, OptionCatalog.TABLE_METHODS);
        OptionCatalog.validateAllowedValue("imageOutput", imageOutput, OptionCatalog.IMAGE_OUTPUTS);
        OptionCatalog.validateAllowedValue("imageFormat", imageFormat, OptionCatalog.IMAGE_FORMATS);
        OptionCatalog.validateAllowedValue("hybrid", hybrid, OptionCatalog.HYBRID_BACKENDS);
        OptionCatalog.validateAllowedValue("hybridMode", hybridMode, OptionCatalog.HYBRID_MODES);

        if (StringUtils.hasText(options.pages()) && !PageRangeValidator.isValid(options.pages())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid pages format. Example: 1,3,5-7");
        }

        return new PdfConversionOptions(
                formats,
                blankToNull(options.password()),
                blankToNull(options.pages()),
                options.keepLineBreaks(),
                options.useStructTree(),
                readingOrder,
                tableMethod,
                imageOutput,
                imageFormat,
                blankToNull(options.imageDir()),
                defaultBoolean(options.includeHeaderFooter(), false),
                defaultBoolean(options.sanitize(), true),
                hybrid,
                hybridMode,
                blankToNull(hybridResolved.hybridUrl()),
                hybridResolved.hybridTimeout() == null ? 30_000L : hybridResolved.hybridTimeout(),
                defaultBoolean(hybridResolved.hybridFallback(), true)
        );
    }

    private PdfConversionOptions enrichOptions(PdfConversionOptions options, Path outputDirectory) {
        String imageDir = options.imageDir();
        if (!StringUtils.hasText(imageDir) && "external".equals(options.imageOutput())) {
            imageDir = outputDirectory.resolve("images").toString();
        }
        return new PdfConversionOptions(
                options.formats(),
                options.password(),
                options.pages(),
                options.keepLineBreaks(),
                options.useStructTree(),
                options.readingOrder(),
                options.tableMethod(),
                options.imageOutput(),
                options.imageFormat(),
                imageDir,
                options.includeHeaderFooter(),
                options.sanitize(),
                options.hybrid(),
                options.hybridMode(),
                options.hybridUrl(),
                options.hybridTimeout(),
                options.hybridFallback()
        );
    }

    private List<GeneratedArtifact> indexArtifacts(Path outputDirectory) throws IOException {
        List<GeneratedArtifact> outputFiles = new ArrayList<>();
        try (var stream = Files.walk(outputDirectory)) {
            stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> {
                        try {
                            String fileId = UUID.randomUUID().toString();
                            GeneratedArtifact artifact = new GeneratedArtifact(
                                    fileId,
                                    inferFormat(file),
                                    file.getFileName().toString(),
                                    detectContentType(file),
                                    Files.size(file),
                                    outputDirectory.relativize(file).toString().replace('\\', '/'),
                                    file.toAbsolutePath().normalize()
                            );
                            artifacts.put(fileId, artifact);
                            outputFiles.add(artifact);
                        } catch (IOException ex) {
                            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to inspect output file", ex);
                        }
                    });
        }
        return outputFiles;
    }

    private List<GeneratedArtifact> rewriteMarkdownImageLinks(List<GeneratedArtifact> outputFiles) throws IOException {
        Map<String, String> replacements = outputFiles.stream()
                .filter(artifact -> "image".equals(artifact.format()))
                .collect(ConcurrentHashMap::new,
                        (map, artifact) -> {
                            String downloadPath = "/api/v1/pdf/files/" + artifact.id();
                            map.put(artifact.absolutePath().toString(), downloadPath);
                            map.put(artifact.absolutePath().toString().replace('\\', '/'), downloadPath);
                            map.put(artifact.relativePath(), downloadPath);
                            map.put(artifact.relativePath().replace('\\', '/'), downloadPath);
                        },
                        Map::putAll);

        if (replacements.isEmpty()) {
            return outputFiles;
        }

        boolean changed = false;
        for (GeneratedArtifact artifact : outputFiles) {
            if (!"markdown".equals(artifact.format())) {
                continue;
            }
            String markdown = Files.readString(artifact.absolutePath());
            String rewritten = markdown;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                rewritten = rewritten.replace(entry.getKey(), entry.getValue());
            }
            if (!rewritten.equals(markdown)) {
                Files.writeString(artifact.absolutePath(), rewritten);
                changed = true;
            }
        }

        if (!changed) {
            return outputFiles;
        }

        List<GeneratedArtifact> refreshedArtifacts = new ArrayList<>(outputFiles.size());
        for (GeneratedArtifact artifact : outputFiles) {
            GeneratedArtifact refreshed = new GeneratedArtifact(
                    artifact.id(),
                    artifact.format(),
                    artifact.filename(),
                    artifact.contentType(),
                    Files.size(artifact.absolutePath()),
                    artifact.relativePath(),
                    artifact.absolutePath()
            );
            artifacts.put(refreshed.id(), refreshed);
            refreshedArtifacts.add(refreshed);
        }
        return refreshedArtifacts;
    }

    private String inferFormat(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".json")) {
            return "json";
        }
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return "html";
        }
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return "markdown";
        }
        if (name.endsWith(".pdf")) {
            return "pdf";
        }
        if (name.endsWith(".txt")) {
            return "text";
        }
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image";
        }
        return "file";
    }

    private String detectContentType(Path file) {
        try {
            String detected = Files.probeContentType(file);
            if (StringUtils.hasText(detected)) {
                return withUtf8IfTextual(detected);
            }
        } catch (IOException ignored) {
        }
        String format = inferFormat(file);
        return switch (format) {
            case "json" -> "application/json;charset=UTF-8";
            case "html" -> "text/html;charset=UTF-8";
            case "markdown" -> "text/markdown;charset=UTF-8";
            case "pdf" -> "application/pdf";
            case "text" -> "text/plain;charset=UTF-8";
            case "image" -> "image/" + (file.getFileName().toString().toLowerCase().endsWith(".png") ? "png" : "jpeg");
            default -> "application/octet-stream";
        };
    }

    private String withUtf8IfTextual(String contentType) {
        String normalized = contentType.toLowerCase();
        if (normalized.startsWith("text/") || normalized.startsWith("application/json")) {
            return normalized.contains("charset=") ? contentType : contentType + ";charset=UTF-8";
        }
        return contentType;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A PDF or supported image file is required.");
        }
        if (uploadMediaType(file) == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only .pdf, .png, .jpg, and .jpeg uploads are supported.");
        }
    }

    private void storeUploadedDocument(MultipartFile file, Path uploadPath) throws IOException {
        String mediaType = uploadMediaType(file);
        if ("pdf".equals(mediaType)) {
            file.transferTo(uploadPath);
            return;
        }
        if ("image".equals(mediaType)) {
            wrapImageInPdf(file, uploadPath);
            return;
        }
        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Only .pdf, .png, .jpg, and .jpeg uploads are supported.");
    }

    private void wrapImageInPdf(MultipartFile file, Path uploadPath) throws IOException {
        ImagePdfSupport.wrapImageInPdf(ImagePdfSupport.readImage(file.getInputStream()), uploadPath);
    }

    private String uploadMediaType(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String lowerName = filename == null ? "" : filename.toLowerCase();
        if (lowerName.endsWith(".pdf")) {
            return "pdf";
        }
        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            return "image";
        }

        String contentType = file.getContentType();
        if ("application/pdf".equalsIgnoreCase(contentType)) {
            return "pdf";
        }
        if ("image/png".equalsIgnoreCase(contentType)
                || "image/jpeg".equalsIgnoreCase(contentType)
                || "image/jpg".equalsIgnoreCase(contentType)) {
            return "image";
        }
        return null;
    }

    /**
     * Returns a copy of {@code options} with hybrid mode forced to
     * {@code docling-fast / full} so that scanned (image-based) PDFs are
     * processed via docling OCR regardless of the caller's setting.
     *
     * <p>If no hybrid URL is configured the original options are returned
     * unchanged – OCR cannot be applied without a docling endpoint.</p>
     */
    private PdfConversionOptions forceDoclingOptions(PdfConversionOptions options) {
        String hybridUrl = StringUtils.hasText(options.hybridUrl()) ? options.hybridUrl() : null;
        if (hybridUrl == null) {
            log.warn("[JOB] Image-based PDF detected but no hybrid URL configured – OCR will be skipped");
            return options;
        }
        return new PdfConversionOptions(
                options.formats(),
                options.password(),
                options.pages(),
                options.keepLineBreaks(),
                options.useStructTree(),
                options.readingOrder(),
                options.tableMethod(),
                options.imageOutput(),
                options.imageFormat(),
                options.imageDir(),
                options.includeHeaderFooter(),
                options.sanitize(),
                "docling-fast",           // force OCR backend
                "full",                   // full mode: docling owns the entire parse
                hybridUrl,
                options.hybridTimeout() != null ? options.hybridTimeout() : 120_000L,
                false                     // no text fallback – OCR is mandatory for image PDFs
        );
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Boolean defaultBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}

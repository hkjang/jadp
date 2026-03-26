package com.example.jadp.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConversionJob {

    private final UUID id;
    private final String sourceFilename;
    private final long sourceSize;
    private final Path uploadPath;
    private final Path outputDirectory;
    private final PdfConversionOptions options;
    private final Instant createdAt;
    private volatile JobStatus status;
    private volatile Instant startedAt;
    private volatile Instant endedAt;
    private volatile Long processingMillis;
    private volatile String error;
    private volatile List<GeneratedArtifact> artifacts;

    public ConversionJob(UUID id,
                         String sourceFilename,
                         long sourceSize,
                         Path uploadPath,
                         Path outputDirectory,
                         PdfConversionOptions options) {
        this.id = id;
        this.sourceFilename = sourceFilename;
        this.sourceSize = sourceSize;
        this.uploadPath = uploadPath;
        this.outputDirectory = outputDirectory;
        this.options = options;
        this.createdAt = Instant.now();
        this.status = JobStatus.PENDING;
        this.artifacts = List.of();
    }

    public synchronized void markRunning() {
        this.status = JobStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public synchronized void markSucceeded(List<GeneratedArtifact> artifacts) {
        this.status = JobStatus.SUCCEEDED;
        this.endedAt = Instant.now();
        this.processingMillis = startedAt == null ? null : endedAt.toEpochMilli() - startedAt.toEpochMilli();
        this.artifacts = List.copyOf(new ArrayList<>(artifacts));
        this.error = null;
    }

    public synchronized void markFailed(String error) {
        this.status = JobStatus.FAILED;
        this.endedAt = Instant.now();
        this.processingMillis = startedAt == null ? null : endedAt.toEpochMilli() - startedAt.toEpochMilli();
        this.error = error;
    }

    public UUID getId() {
        return id;
    }

    public String getSourceFilename() {
        return sourceFilename;
    }

    public long getSourceSize() {
        return sourceSize;
    }

    public Path getUploadPath() {
        return uploadPath;
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public PdfConversionOptions getOptions() {
        return options;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public JobStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Long getProcessingMillis() {
        return processingMillis;
    }

    public String getError() {
        return error;
    }

    public List<GeneratedArtifact> getArtifacts() {
        return artifacts;
    }
}


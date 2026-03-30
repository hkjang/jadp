package com.example.jadp.service;

import com.example.jadp.model.PiiDetectionResult;
import com.example.jadp.model.PiiFinding;
import com.example.jadp.support.ApiException;
import com.example.jadp.support.PiiFindingMergeSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ImageNativePiiDetector {

    private final HybridDoclingDirectPiiDetector hybridDoclingDirectPiiDetector;
    private final VllmVisionPiiDetector vllmVisionPiiDetector;

    public ImageNativePiiDetector(HybridDoclingDirectPiiDetector hybridDoclingDirectPiiDetector,
                                  VllmVisionPiiDetector vllmVisionPiiDetector) {
        this.hybridDoclingDirectPiiDetector = hybridDoclingDirectPiiDetector;
        this.vllmVisionPiiDetector = vllmVisionPiiDetector;
    }

    public boolean isConfigured() {
        return hybridDoclingDirectPiiDetector.isConfigured() || vllmVisionPiiDetector.isConfigured();
    }

    public PiiDetectionResult detect(UUID documentId,
                                     String originalFilename,
                                     String contentType,
                                     Path sourceFile,
                                     Path workingDirectory) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Image PII detection requires either Hybrid Docling or vLLM OCR to be configured.");
        }

        List<PiiFinding> findings = new ArrayList<>();
        if (hybridDoclingDirectPiiDetector.isConfigured()) {
            findings = new ArrayList<>(PiiFindingMergeSupport.mergeFindings(
                    findings,
                    hybridDoclingDirectPiiDetector.detectImage(sourceFile, contentType, workingDirectory)
            ));
        }
        if (vllmVisionPiiDetector.isConfigured()) {
            try {
                findings = new ArrayList<>(PiiFindingMergeSupport.mergeFindings(
                        findings,
                        vllmVisionPiiDetector.detectFindings(sourceFile, contentType, 1, "vllm-vision")
                ));
            } catch (IOException ex) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "vLLM OCR invocation failed: " + ex.getMessage(), ex);
            }
        }

        return new PiiDetectionResult(
                documentId,
                originalFilename,
                contentType,
                "image",
                1,
                sourceFile,
                List.copyOf(findings)
        );
    }
}

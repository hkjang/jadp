package com.example.jadp.service;

import com.example.jadp.config.HybridProcessingProperties;
import com.example.jadp.model.PdfConversionOptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridOptionsResolverTest {

    @Test
    void keepsHybridOffForPdfRequestsWhenAutoApplyIsDisabled() {
        HybridProcessingProperties properties = new HybridProcessingProperties();
        properties.setEnabled(true);
        properties.setAutoApplyToRequests(false);
        properties.setAutoApplyToPii(true);

        HybridOptionsResolver resolver = new HybridOptionsResolver(properties);

        PdfConversionOptions resolved = resolver.applyDefaults(blankOptions(), HybridUsage.PDF_REQUEST);

        assertThat(resolved.hybrid()).isEqualTo("off");
        assertThat(resolved.hybridMode()).isEqualTo("auto");
    }

    @Test
    void autoAppliesHybridForPiiDetectionWhenEnabled() {
        HybridProcessingProperties properties = new HybridProcessingProperties();
        properties.setEnabled(true);
        properties.setAutoApplyToPii(true);
        properties.setBackend("docling-fast");
        properties.setUrl("http://jadp-hybrid:5002");
        properties.setTimeoutMillis(120_000L);
        properties.setFallback(true);

        HybridOptionsResolver resolver = new HybridOptionsResolver(properties);

        PdfConversionOptions resolved = resolver.applyDefaults(blankOptions(), HybridUsage.PII_DETECTION);

        assertThat(resolved.hybrid()).isEqualTo("docling-fast");
        assertThat(resolved.hybridMode()).isEqualTo("auto");
        assertThat(resolved.hybridUrl()).isEqualTo("http://jadp-hybrid:5002");
        assertThat(resolved.hybridTimeout()).isEqualTo(120_000L);
        assertThat(resolved.hybridFallback()).isTrue();
    }

    @Test
    void prefersFullModeWhenConfigured() {
        HybridProcessingProperties properties = new HybridProcessingProperties();
        properties.setEnabled(true);
        properties.setAutoApplyToRequests(true);
        properties.setPreferFullMode(true);

        HybridOptionsResolver resolver = new HybridOptionsResolver(properties);

        PdfConversionOptions resolved = resolver.applyDefaults(blankOptions(), HybridUsage.PDF_REQUEST);

        assertThat(resolved.hybrid()).isEqualTo("docling-fast");
        assertThat(resolved.hybridMode()).isEqualTo("full");
    }

    @Test
    void preservesExplicitRequestOverrides() {
        HybridProcessingProperties properties = new HybridProcessingProperties();
        properties.setEnabled(true);
        properties.setAutoApplyToRequests(true);
        properties.setPreferFullMode(true);

        HybridOptionsResolver resolver = new HybridOptionsResolver(properties);
        PdfConversionOptions explicit = new PdfConversionOptions(
                List.of("json"),
                null,
                null,
                false,
                false,
                "xycut",
                "default",
                "off",
                "png",
                null,
                false,
                true,
                "off",
                "auto",
                null,
                30_000L,
                true
        );

        PdfConversionOptions resolved = resolver.applyDefaults(explicit, HybridUsage.PDF_REQUEST);

        assertThat(resolved.hybrid()).isEqualTo("off");
        assertThat(resolved.hybridMode()).isEqualTo("auto");
    }

    private PdfConversionOptions blankOptions() {
        return new PdfConversionOptions(
                List.of("json"),
                null,
                null,
                false,
                false,
                "xycut",
                "default",
                "off",
                "png",
                null,
                false,
                true,
                null,
                null,
                null,
                null,
                null
        );
    }
}

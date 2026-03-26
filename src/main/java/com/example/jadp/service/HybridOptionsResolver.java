package com.example.jadp.service;

import com.example.jadp.config.HybridProcessingProperties;
import com.example.jadp.model.PdfConversionOptions;
import com.example.jadp.support.OptionCatalog;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class HybridOptionsResolver {

    private final HybridProcessingProperties properties;

    public HybridOptionsResolver(HybridProcessingProperties properties) {
        this.properties = properties;
    }

    public PdfConversionOptions applyDefaults(PdfConversionOptions options, HybridUsage usage) {
        boolean explicitHybrid = StringUtils.hasText(options.hybrid());
        boolean explicitMode = StringUtils.hasText(options.hybridMode());
        boolean explicitUrl = StringUtils.hasText(options.hybridUrl());

        String hybrid = explicitHybrid
                ? OptionCatalog.normalizeOptional(options.hybrid(), OptionCatalog.DEFAULT_HYBRID)
                : defaultHybrid(usage);
        String hybridMode = explicitMode
                ? OptionCatalog.normalizeOptional(options.hybridMode(), OptionCatalog.DEFAULT_HYBRID_MODE)
                : defaultMode(hybrid);
        String hybridUrl = explicitUrl ? options.hybridUrl().trim() : defaultUrl(hybrid);
        Long hybridTimeout = options.hybridTimeout() != null ? options.hybridTimeout() : defaultTimeout(hybrid);
        Boolean hybridFallback = options.hybridFallback() != null ? options.hybridFallback() : defaultFallback(hybrid);

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
                hybrid,
                hybridMode,
                hybridUrl,
                hybridTimeout,
                hybridFallback
        );
    }

    public HybridProcessingProperties properties() {
        return properties;
    }

    private String defaultHybrid(HybridUsage usage) {
        if (!properties.isEnabled()) {
            return OptionCatalog.DEFAULT_HYBRID;
        }
        return switch (usage) {
            case PDF_REQUEST -> properties.isAutoApplyToRequests() ? properties.getBackend() : OptionCatalog.DEFAULT_HYBRID;
            case PII_DETECTION -> properties.isAutoApplyToPii() ? properties.getBackend() : OptionCatalog.DEFAULT_HYBRID;
        };
    }

    private String defaultMode(String hybrid) {
        if (OptionCatalog.DEFAULT_HYBRID.equals(hybrid)) {
            return OptionCatalog.DEFAULT_HYBRID_MODE;
        }
        if (properties.isPreferFullMode()) {
            return "full";
        }
        return StringUtils.hasText(properties.getMode()) ? properties.getMode().trim() : OptionCatalog.DEFAULT_HYBRID_MODE;
    }

    private String defaultUrl(String hybrid) {
        if (OptionCatalog.DEFAULT_HYBRID.equals(hybrid) || !StringUtils.hasText(properties.getUrl())) {
            return null;
        }
        return properties.getUrl().trim();
    }

    private Long defaultTimeout(String hybrid) {
        if (OptionCatalog.DEFAULT_HYBRID.equals(hybrid)) {
            return 30_000L;
        }
        return properties.getTimeoutMillis();
    }

    private Boolean defaultFallback(String hybrid) {
        if (OptionCatalog.DEFAULT_HYBRID.equals(hybrid)) {
            return true;
        }
        return properties.isFallback();
    }
}

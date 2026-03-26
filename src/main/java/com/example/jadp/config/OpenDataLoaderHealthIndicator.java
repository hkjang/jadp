package com.example.jadp.config;

import com.example.jadp.service.PdfConversionEngine;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("openDataLoader")
public class OpenDataLoaderHealthIndicator implements HealthIndicator {

    private final PdfConversionEngine pdfConversionEngine;
    private final OpenDataLoaderProperties properties;

    public OpenDataLoaderHealthIndicator(PdfConversionEngine pdfConversionEngine,
                                         OpenDataLoaderProperties properties) {
        this.pdfConversionEngine = pdfConversionEngine;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (pdfConversionEngine.isAvailable()) {
            return Health.up()
                    .withDetail("versionHint", properties.getVersionHint())
                    .withDetail("engine", pdfConversionEngine.getEngineName())
                    .build();
        }
        return Health.down()
                .withDetail("versionHint", properties.getVersionHint())
                .withDetail("engine", pdfConversionEngine.getEngineName())
                .withDetail("reason", pdfConversionEngine.getAvailabilityMessage())
                .build();
    }
}


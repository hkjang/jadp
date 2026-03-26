package com.example.jadp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.opendataloader.hybrid")
public class HybridProcessingProperties {

    private boolean enabled = false;
    private String backend = "docling-fast";
    private String mode = "auto";
    private String url = "http://localhost:5002";
    private long timeoutMillis = 60_000L;
    private boolean fallback = true;
    private boolean autoApplyToRequests = false;
    private boolean autoApplyToPii = true;
    private boolean preferFullMode = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public boolean isFallback() {
        return fallback;
    }

    public void setFallback(boolean fallback) {
        this.fallback = fallback;
    }

    public boolean isAutoApplyToRequests() {
        return autoApplyToRequests;
    }

    public void setAutoApplyToRequests(boolean autoApplyToRequests) {
        this.autoApplyToRequests = autoApplyToRequests;
    }

    public boolean isAutoApplyToPii() {
        return autoApplyToPii;
    }

    public void setAutoApplyToPii(boolean autoApplyToPii) {
        this.autoApplyToPii = autoApplyToPii;
    }

    public boolean isPreferFullMode() {
        return preferFullMode;
    }

    public void setPreferFullMode(boolean preferFullMode) {
        this.preferFullMode = preferFullMode;
    }
}

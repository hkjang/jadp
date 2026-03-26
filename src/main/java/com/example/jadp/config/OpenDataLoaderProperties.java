package com.example.jadp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.opendataloader")
public class OpenDataLoaderProperties {

    /**
     * Reference version documented in the project. The implementation uses reflection
     * so that minor API differences can be tolerated across validated releases.
     */
    private String versionHint = "1.3.0";

    public String getVersionHint() {
        return versionHint;
    }

    public void setVersionHint(String versionHint) {
        this.versionHint = versionHint;
    }
}


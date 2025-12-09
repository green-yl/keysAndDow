package com.example.keys.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppProperties {
    @Value("${app.cache-root:}")
    private String cacheRoot;

    @Value("${app.releases-root:}")
    private String releasesRoot;

    @Value("${app.import-allowlist:github.com,raw.githubusercontent.com,gitlab.com}")
    private String importAllowlist;

    public String getCacheRoot() {
        if (cacheRoot == null || cacheRoot.isBlank()) {
            boolean isWin = System.getProperty("os.name").toLowerCase().startsWith("win");
            return isWin ? "D:/srv/source_cache" : "/srv/source_cache";
        }
        return cacheRoot;
    }

    public String getReleasesRoot() {
        if (releasesRoot == null || releasesRoot.isBlank()) {
            boolean isWin = System.getProperty("os.name").toLowerCase().startsWith("win");
            return isWin ? "D:/srv/releases_pool" : "/srv/releases_pool";
        }
        return releasesRoot;
    }

    public String[] getImportAllowlist() {
        return importAllowlist.split(",");
    }
}








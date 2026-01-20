package com.example.translationlayer.config;

import org.springframework.stereotype.Component;

/**
 * SMB/NAS connection configuration.
 * Delegates to AppSettings for actual values - this is a facade for backward
 * compatibility.
 */
@Component
public class SmbConfig {

    private final AppSettings appSettings;

    public SmbConfig(AppSettings appSettings) {
        this.appSettings = appSettings;
    }

    public boolean isConfigured() {
        return !getHost().isBlank() && !getShare().isBlank();
    }

    public String getHost() {
        return appSettings.getSmbHost();
    }

    public String getShare() {
        return appSettings.getSmbShare();
    }

    public String getUsername() {
        return appSettings.getSmbUsername();
    }

    public String getPassword() {
        return appSettings.getSmbPassword();
    }

    public String getDomain() {
        return appSettings.getSmbDomain();
    }

    /**
     * Update all settings and persist.
     */
    public void updateSettings(String host, String share, String username, String password, String domain) {
        appSettings.setSmbHost(host != null ? host.trim() : "");
        appSettings.setSmbShare(share != null ? share.trim() : "");
        appSettings.setSmbUsername(username != null ? username.trim() : "");
        // Only update password if not empty (to preserve existing password when UI
        // sends placeholder)
        if (password != null && !password.isBlank()) {
            appSettings.setSmbPassword(password);
        }
        appSettings.setSmbDomain(domain != null ? domain.trim() : "");
        appSettings.saveSettings();
    }

    /**
     * Get settings as a record (without password for security).
     */
    public record SmbSettings(String host, String share, String username, String domain, boolean configured) {
    }

    public SmbSettings getSettings() {
        return new SmbSettings(getHost(), getShare(), getUsername(), getDomain(), isConfigured());
    }
}

package com.example.translationlayer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration for SMB/NAS connection settings.
 * Persists settings to smb-config.json in the cache directory.
 */
@Component
public class SmbConfig {

    private static final Logger log = LoggerFactory.getLogger(SmbConfig.class);
    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".subtitle-cache",
            "smb-config.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    private String host = "";
    private String share = "";
    private String username = "";
    private String password = "";
    private String domain = "";

    @PostConstruct
    public void init() {
        // Ensure cache directory exists
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
        } catch (IOException e) {
            log.warn("Could not create cache directory: {}", e.getMessage());
        }
        loadConfig();
    }

    public boolean isConfigured() {
        return !host.isBlank() && !share.isBlank();
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host != null ? host.trim() : "";
    }

    public String getShare() {
        return share;
    }

    public void setShare(String share) {
        this.share = share != null ? share.trim() : "";
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username != null ? username.trim() : "";
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password != null ? password : "";
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain != null ? domain.trim() : "";
    }

    /**
     * Update all settings and persist to config file.
     */
    public void updateSettings(String host, String share, String username, String password, String domain) {
        setHost(host);
        setShare(share);
        setUsername(username);
        setPassword(password);
        setDomain(domain);
        saveConfig();
        log.info("SMB settings updated - host: {}, share: {}", this.host, this.share);
    }

    /**
     * Get settings as a map (without password for security).
     */
    public record SmbSettings(String host, String share, String username, String domain, boolean configured) {
    }

    public SmbSettings getSettings() {
        return new SmbSettings(host, share, username, domain, isConfigured());
    }

    private void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE);
                var config = mapper.readTree(json);
                if (config.has("host"))
                    this.host = config.get("host").asText("");
                if (config.has("share"))
                    this.share = config.get("share").asText("");
                if (config.has("username"))
                    this.username = config.get("username").asText("");
                if (config.has("password"))
                    this.password = config.get("password").asText("");
                if (config.has("domain"))
                    this.domain = config.get("domain").asText("");
                log.info("Loaded SMB config - host: {}, share: {}", host, share);
            }
        } catch (IOException e) {
            log.warn("Could not load SMB config: {}", e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            ObjectNode config = mapper.createObjectNode();
            config.put("host", host);
            config.put("share", share);
            config.put("username", username);
            config.put("password", password);
            config.put("domain", domain);
            Files.writeString(CONFIG_FILE, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        } catch (IOException e) {
            log.error("Failed to save SMB config", e);
        }
    }
}

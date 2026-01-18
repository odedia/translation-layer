package com.example.translationlayer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Unified application settings that persist across restarts.
 * Stores all configurable options in a single JSON file.
 */
@Component
public class AppSettings {

    private static final Logger log = LoggerFactory.getLogger(AppSettings.class);
    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".subtitle-cache",
            "app-settings.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    // OpenSubtitles Configuration
    private String openSubtitlesApiKey = "";
    private String openSubtitlesUsername = "";
    private String openSubtitlesPassword = "";

    // OpenAI Configuration
    private String openAiApiKey = "";

    // Model Configuration
    private String modelProvider = "ollama"; // "ollama" or "openai"
    private String ollamaModel = "translategema2:4b";
    private String openAiModel = "gpt-4o-mini";
    private String ollamaBaseUrl = "http://localhost:11434";

    // Translation Settings
    private String targetLanguage = "Hebrew (RTL)";
    private boolean skipHearingImpaired = false;

    // SMB Settings (migrated from SmbConfig)
    private String smbHost = "";
    private String smbShare = "";
    private String smbUsername = "";
    private String smbPassword = "";
    private String smbDomain = "";

    @PostConstruct
    private void init() {
        loadSettings();
    }

    public void loadSettings() {
        if (!Files.exists(CONFIG_FILE)) {
            log.info("No settings file found, using defaults");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(CONFIG_FILE.toFile(), Map.class);

            openSubtitlesApiKey = getString(data, "openSubtitlesApiKey", "");
            openSubtitlesUsername = getString(data, "openSubtitlesUsername", "");
            openSubtitlesPassword = getString(data, "openSubtitlesPassword", "");
            openAiApiKey = getString(data, "openAiApiKey", "");
            modelProvider = getString(data, "modelProvider", "ollama");
            ollamaModel = getString(data, "ollamaModel", "translategema2:4b");
            openAiModel = getString(data, "openAiModel", "gpt-4o-mini");
            ollamaBaseUrl = getString(data, "ollamaBaseUrl", "http://localhost:11434");
            targetLanguage = getString(data, "targetLanguage", "Hebrew (RTL)");
            skipHearingImpaired = getBoolean(data, "skipHearingImpaired", false);
            smbHost = getString(data, "smbHost", "");
            smbShare = getString(data, "smbShare", "");
            smbUsername = getString(data, "smbUsername", "");
            smbPassword = getString(data, "smbPassword", "");
            smbDomain = getString(data, "smbDomain", "");

            log.info("Loaded settings: provider={}, model={}", modelProvider,
                    "openai".equals(modelProvider) ? openAiModel : ollamaModel);
        } catch (IOException e) {
            log.error("Failed to load settings", e);
        }
    }

    public void saveSettings() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());

            Map<String, Object> data = new HashMap<>();
            data.put("openSubtitlesApiKey", openSubtitlesApiKey);
            data.put("openSubtitlesUsername", openSubtitlesUsername);
            data.put("openSubtitlesPassword", openSubtitlesPassword);
            data.put("openAiApiKey", openAiApiKey);
            data.put("modelProvider", modelProvider);
            data.put("ollamaModel", ollamaModel);
            data.put("openAiModel", openAiModel);
            data.put("ollamaBaseUrl", ollamaBaseUrl);
            data.put("targetLanguage", targetLanguage);
            data.put("skipHearingImpaired", skipHearingImpaired);
            data.put("smbHost", smbHost);
            data.put("smbShare", smbShare);
            data.put("smbUsername", smbUsername);
            data.put("smbPassword", smbPassword);
            data.put("smbDomain", smbDomain);

            mapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.toFile(), data);
            log.info("Saved settings to {}", CONFIG_FILE);
        } catch (IOException e) {
            log.error("Failed to save settings", e);
        }
    }

    private String getString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private boolean getBoolean(Map<String, Object> data, String key, boolean defaultValue) {
        Object value = data.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    // Getters and Setters
    public String getOpenSubtitlesApiKey() {
        return openSubtitlesApiKey;
    }

    public void setOpenSubtitlesApiKey(String key) {
        this.openSubtitlesApiKey = key;
    }

    public String getOpenSubtitlesUsername() {
        return openSubtitlesUsername;
    }

    public void setOpenSubtitlesUsername(String username) {
        this.openSubtitlesUsername = username;
    }

    public String getOpenSubtitlesPassword() {
        return openSubtitlesPassword;
    }

    public void setOpenSubtitlesPassword(String password) {
        this.openSubtitlesPassword = password;
    }

    public String getOpenAiApiKey() {
        return openAiApiKey;
    }

    public void setOpenAiApiKey(String key) {
        this.openAiApiKey = key;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String provider) {
        this.modelProvider = provider;
    }

    public String getOllamaModel() {
        return ollamaModel;
    }

    public void setOllamaModel(String model) {
        this.ollamaModel = model;
    }

    public String getOpenAiModel() {
        return openAiModel;
    }

    public void setOpenAiModel(String model) {
        this.openAiModel = model;
    }

    public String getOllamaBaseUrl() {
        return ollamaBaseUrl;
    }

    public void setOllamaBaseUrl(String url) {
        this.ollamaBaseUrl = url;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public void setTargetLanguage(String lang) {
        this.targetLanguage = lang;
    }

    public boolean isSkipHearingImpaired() {
        return skipHearingImpaired;
    }

    public void setSkipHearingImpaired(boolean skip) {
        this.skipHearingImpaired = skip;
    }

    public String getSmbHost() {
        return smbHost;
    }

    public void setSmbHost(String host) {
        this.smbHost = host;
    }

    public String getSmbShare() {
        return smbShare;
    }

    public void setSmbShare(String share) {
        this.smbShare = share;
    }

    public String getSmbUsername() {
        return smbUsername;
    }

    public void setSmbUsername(String username) {
        this.smbUsername = username;
    }

    public String getSmbPassword() {
        return smbPassword;
    }

    public void setSmbPassword(String password) {
        this.smbPassword = password;
    }

    public String getSmbDomain() {
        return smbDomain;
    }

    public void setSmbDomain(String domain) {
        this.smbDomain = domain;
    }

    public boolean isOpenAiConfigured() {
        return "openai".equals(modelProvider) && !openAiApiKey.isBlank();
    }

    public boolean isOllamaConfigured() {
        return "ollama".equals(modelProvider) && !ollamaModel.isBlank();
    }

    public String getActiveModel() {
        return "openai".equals(modelProvider) ? openAiModel : ollamaModel;
    }

    /**
     * Check if this is the first run (no settings file exists).
     */
    public boolean isFirstRun() {
        return !Files.exists(CONFIG_FILE);
    }

    /**
     * Check if minimum required settings are configured.
     */
    public boolean isConfigured() {
        // Need at least: model provider configured, and if using OpenAI, API key must
        // be set
        if ("openai".equals(modelProvider)) {
            return !openAiApiKey.isBlank();
        }
        // Ollama just needs a model name (no auth required)
        return !ollamaModel.isBlank();
    }
}

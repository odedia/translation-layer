package com.example.translationlayer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for supported languages with RTL detection.
 * Persists selected language to config.json.
 */
@Component
public class LanguageConfig {

    private static final Logger log = LoggerFactory.getLogger(LanguageConfig.class);
    private static final Path CONFIG_FILE = Path.of("./language-config.json");
    private static final ObjectMapper mapper = new ObjectMapper();

    // RTL languages
    private static final Set<String> RTL_LANGUAGES = Set.of(
            "Hebrew", "Arabic", "Persian", "Urdu");

    // Top 30 languages (ordered for dropdown display)
    private static final Map<String, String> SUPPORTED_LANGUAGES = new LinkedHashMap<>();
    static {
        SUPPORTED_LANGUAGES.put("Arabic", "ar");
        SUPPORTED_LANGUAGES.put("Chinese (Simplified)", "zh-CN");
        SUPPORTED_LANGUAGES.put("Chinese (Traditional)", "zh-TW");
        SUPPORTED_LANGUAGES.put("Czech", "cs");
        SUPPORTED_LANGUAGES.put("Danish", "da");
        SUPPORTED_LANGUAGES.put("Dutch", "nl");
        SUPPORTED_LANGUAGES.put("Finnish", "fi");
        SUPPORTED_LANGUAGES.put("French", "fr");
        SUPPORTED_LANGUAGES.put("German", "de");
        SUPPORTED_LANGUAGES.put("Greek", "el");
        SUPPORTED_LANGUAGES.put("Hebrew", "he");
        SUPPORTED_LANGUAGES.put("Hindi", "hi");
        SUPPORTED_LANGUAGES.put("Hungarian", "hu");
        SUPPORTED_LANGUAGES.put("Indonesian", "id");
        SUPPORTED_LANGUAGES.put("Italian", "it");
        SUPPORTED_LANGUAGES.put("Japanese", "ja");
        SUPPORTED_LANGUAGES.put("Korean", "ko");
        SUPPORTED_LANGUAGES.put("Malay", "ms");
        SUPPORTED_LANGUAGES.put("Norwegian", "no");
        SUPPORTED_LANGUAGES.put("Persian", "fa");
        SUPPORTED_LANGUAGES.put("Polish", "pl");
        SUPPORTED_LANGUAGES.put("Portuguese", "pt");
        SUPPORTED_LANGUAGES.put("Romanian", "ro");
        SUPPORTED_LANGUAGES.put("Russian", "ru");
        SUPPORTED_LANGUAGES.put("Spanish", "es");
        SUPPORTED_LANGUAGES.put("Swedish", "sv");
        SUPPORTED_LANGUAGES.put("Thai", "th");
        SUPPORTED_LANGUAGES.put("Turkish", "tr");
        SUPPORTED_LANGUAGES.put("Ukrainian", "uk");
        SUPPORTED_LANGUAGES.put("Urdu", "ur");
        SUPPORTED_LANGUAGES.put("Vietnamese", "vi");
    }

    private String currentLanguage = "Hebrew"; // Default

    @PostConstruct
    public void init() {
        loadConfig();
    }

    /**
     * Get all supported languages.
     */
    public Map<String, String> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    /**
     * Get the current target language.
     */
    public String getTargetLanguage() {
        return currentLanguage;
    }

    /**
     * Set the target language and persist to config.
     */
    public void setTargetLanguage(String language) {
        if (SUPPORTED_LANGUAGES.containsKey(language)) {
            this.currentLanguage = language;
            saveConfig();
            log.info("Target language changed to: {}", language);
        } else {
            log.warn("Unsupported language: {}", language);
        }
    }

    /**
     * Check if the current target language is RTL.
     */
    public boolean isCurrentLanguageRtl() {
        return RTL_LANGUAGES.contains(currentLanguage);
    }

    /**
     * Check if a specific language is RTL.
     */
    public boolean isRtlLanguage(String language) {
        return RTL_LANGUAGES.contains(language);
    }

    /**
     * Get the language code for the current language.
     */
    public String getLanguageCode() {
        return SUPPORTED_LANGUAGES.getOrDefault(currentLanguage, "he");
    }

    private void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE);
                var config = mapper.readTree(json);
                if (config.has("targetLanguage")) {
                    String lang = config.get("targetLanguage").asText();
                    if (SUPPORTED_LANGUAGES.containsKey(lang)) {
                        this.currentLanguage = lang;
                        log.info("Loaded target language from config: {}", lang);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not load language config, using default: {}", currentLanguage);
        }
    }

    private void saveConfig() {
        try {
            String json = String.format("{\"targetLanguage\":\"%s\"}", currentLanguage);
            Files.writeString(CONFIG_FILE, json);
        } catch (IOException e) {
            log.error("Failed to save language config", e);
        }
    }
}

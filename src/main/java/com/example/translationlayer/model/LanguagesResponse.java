package com.example.translationlayer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Languages response matching OpenSubtitles API format.
 */
public record LanguagesResponse(
        @JsonProperty("data") List<LanguageData> data) {
    public record LanguageData(
            @JsonProperty("language_code") String languageCode,
            @JsonProperty("language_name") String languageName) {
    }
}

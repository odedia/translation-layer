package com.example.translationlayer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Login response matching OpenSubtitles API format.
 */
public record LoginResponse(
        @JsonProperty("user") UserInfo user,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("token") String token,
        @JsonProperty("status") int status) {
    public record UserInfo(
            @JsonProperty("allowed_downloads") int allowedDownloads,
            @JsonProperty("allowed_translations") int allowedTranslations,
            @JsonProperty("level") String level,
            @JsonProperty("user_id") int userId,
            @JsonProperty("ext_installed") boolean extInstalled,
            @JsonProperty("vip") boolean vip) {
    }
}

package com.example.translationlayer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User info response matching OpenSubtitles API format.
 */
public record UserInfoResponse(
                @JsonProperty("data") UserData data) {
        public record UserData(
                        @JsonProperty("allowed_downloads") int allowedDownloads,
                        @JsonProperty("allowed_translations") int allowedTranslations,
                        @JsonProperty("level") String level,
                        @JsonProperty("user_id") int userId,
                        @JsonProperty("ext_installed") boolean extInstalled,
                        @JsonProperty("vip") boolean vip,
                        @JsonProperty("downloads_count") int downloadsCount,
                        @JsonProperty("remaining_downloads") int remainingDownloads) {
        }
}

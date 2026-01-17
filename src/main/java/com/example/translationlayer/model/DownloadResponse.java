package com.example.translationlayer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Download response matching OpenSubtitles API format.
 */
public record DownloadResponse(
        @JsonProperty("link") String link,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("requests") int requests,
        @JsonProperty("remaining") int remaining,
        @JsonProperty("message") String message,
        @JsonProperty("reset_time") String resetTime,
        @JsonProperty("reset_time_utc") String resetTimeUtc) {
}

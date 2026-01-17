package com.example.translationlayer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Download request matching OpenSubtitles API format.
 */
public record DownloadRequest(
        @JsonProperty("file_id") int fileId,
        @JsonProperty("sub_format") String subFormat,
        @JsonProperty("file_name") String fileName,
        @JsonProperty("in_fps") Double inFps,
        @JsonProperty("out_fps") Double outFps,
        @JsonProperty("timeshift") Double timeshift,
        @JsonProperty("force_download") Boolean forceDownload) {
    public DownloadRequest {
        if (subFormat == null) {
            subFormat = "srt";
        }
    }
}

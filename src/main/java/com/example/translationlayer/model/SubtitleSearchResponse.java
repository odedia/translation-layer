package com.example.translationlayer.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Subtitle search response matching OpenSubtitles API format.
 * Using nullable types (Integer, Double, Boolean) to handle null values from
 * API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubtitleSearchResponse(
                @JsonProperty("total_pages") Integer totalPages,
                @JsonProperty("total_count") Integer totalCount,
                @JsonProperty("per_page") Integer perPage,
                @JsonProperty("page") Integer page,
                @JsonProperty("data") List<SubtitleData> data) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SubtitleData(
                        @JsonProperty("id") String id,
                        @JsonProperty("type") String type,
                        @JsonProperty("attributes") SubtitleAttributes attributes) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SubtitleAttributes(
                        @JsonProperty("subtitle_id") String subtitleId,
                        @JsonProperty("language") String language,
                        @JsonProperty("download_count") Integer downloadCount,
                        @JsonProperty("new_download_count") Integer newDownloadCount,
                        @JsonProperty("hearing_impaired") Boolean hearingImpaired,
                        @JsonProperty("hd") Boolean hd,
                        @JsonProperty("fps") Double fps,
                        @JsonProperty("votes") Integer votes,
                        @JsonProperty("ratings") Double ratings,
                        @JsonProperty("from_trusted") Boolean fromTrusted,
                        @JsonProperty("foreign_parts_only") Boolean foreignPartsOnly,
                        @JsonProperty("upload_date") String uploadDate,
                        @JsonProperty("ai_translated") Boolean aiTranslated,
                        @JsonProperty("machine_translated") Boolean machineTranslated,
                        @JsonProperty("release") String release,
                        @JsonProperty("comments") String comments,
                        @JsonProperty("legacy_subtitle_id") Integer legacySubtitleId,
                        @JsonProperty("uploader") Uploader uploader,
                        @JsonProperty("feature_details") FeatureDetails featureDetails,
                        @JsonProperty("url") String url,
                        @JsonProperty("related_links") List<RelatedLink> relatedLinks,
                        @JsonProperty("files") List<SubtitleFile> files) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Uploader(
                        @JsonProperty("uploader_id") Integer uploaderId,
                        @JsonProperty("name") String name,
                        @JsonProperty("rank") String rank) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record FeatureDetails(
                        @JsonProperty("feature_id") Integer featureId,
                        @JsonProperty("feature_type") String featureType,
                        @JsonProperty("year") Integer year,
                        @JsonProperty("title") String title,
                        @JsonProperty("movie_name") String movieName,
                        @JsonProperty("imdb_id") Integer imdbId,
                        @JsonProperty("tmdb_id") Integer tmdbId) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record RelatedLink(
                        @JsonProperty("label") String label,
                        @JsonProperty("url") String url,
                        @JsonProperty("img_url") String imgUrl) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SubtitleFile(
                        @JsonProperty("file_id") Integer fileId,
                        @JsonProperty("cd_number") Integer cdNumber,
                        @JsonProperty("file_name") String fileName) {
        }
}

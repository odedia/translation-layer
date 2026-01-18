package com.example.translationlayer.service;

import com.example.translationlayer.config.AppSettings;
import com.example.translationlayer.model.SubtitleSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * Client for OpenSubtitles.org REST API.
 * Handles authentication and proxying subtitle searches/downloads.
 */
@Service
public class OpenSubtitlesClient {

    private static final Logger log = LoggerFactory.getLogger(OpenSubtitlesClient.class);

    @Value("${opensubtitles.base-url:https://api.opensubtitles.com/api/v1}")
    private String baseUrl;

    private final AppSettings appSettings;
    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    private String authToken;

    public OpenSubtitlesClient(RestClient.Builder restClientBuilder, AppSettings appSettings) {
        this.restClient = restClientBuilder.build();
        this.jsonMapper = JsonMapper.builder().build();
        this.appSettings = appSettings;
    }

    @PostConstruct
    public void init() {
        // Login on startup if credentials are provided
        String username = appSettings.getOpenSubtitlesUsername();
        if (username != null && !username.isBlank()) {
            try {
                login();
            } catch (Exception e) {
                log.warn("Failed to login to OpenSubtitles on startup: {}", e.getMessage());
            }
        }
    }

    /**
     * Authenticate with OpenSubtitles and get JWT token.
     */
    public void login() {
        String username = appSettings.getOpenSubtitlesUsername();
        String password = appSettings.getOpenSubtitlesPassword();

        log.info("Logging in to OpenSubtitles as user: {}", username);

        Map<String, String> body = Map.of(
                "username", username,
                "password", password);

        try {
            String response = restClient.post()
                    .uri(baseUrl + "/login")
                    .headers(this::addCommonHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode json = jsonMapper.readTree(response);
            this.authToken = json.path("token").asText();

            log.info("Successfully logged in to OpenSubtitles. Token obtained.");
        } catch (Exception e) {
            log.error("Failed to login to OpenSubtitles", e);
            throw new RuntimeException("OpenSubtitles login failed: " + e.getMessage(), e);
        }
    }

    /**
     * Search for English subtitles on OpenSubtitles.
     */
    public SubtitleSearchResponse searchSubtitles(String query, String imdbId, String tmdbId,
            String movieHash, Integer page) {
        ensureAuthenticated();

        // Build query parameters
        StringBuilder url = new StringBuilder(baseUrl + "/subtitles?languages=en");

        if (query != null && !query.isBlank()) {
            url.append("&query=").append(encodeParam(query));
        }
        if (imdbId != null && !imdbId.isBlank()) {
            url.append("&imdb_id=").append(imdbId.replace("tt", ""));
        }
        if (tmdbId != null && !tmdbId.isBlank()) {
            url.append("&tmdb_id=").append(tmdbId);
        }
        if (movieHash != null && !movieHash.isBlank()) {
            url.append("&moviehash=").append(movieHash);
        }
        if (page != null && page > 0) {
            url.append("&page=").append(page);
        }

        log.info("Searching OpenSubtitles: {}", url);

        try {
            String response = restClient.get()
                    .uri(url.toString())
                    .headers(this::addAuthHeaders)
                    .retrieve()
                    .body(String.class);

            // Parse and return the response
            SubtitleSearchResponse searchResponse = jsonMapper.readValue(response, SubtitleSearchResponse.class);

            // Mark all results as Hebrew (since we'll translate them)
            return transformToHebrew(searchResponse);
        } catch (Exception e) {
            log.error("Failed to search OpenSubtitles", e);
            throw new RuntimeException("OpenSubtitles search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Result of downloading a subtitle, including filename.
     */
    public record DownloadResult(String content, String fileName) {
    }

    /**
     * Download a subtitle file from OpenSubtitles.
     * Returns the raw subtitle content (SRT/VTT) and the original filename.
     */
    public DownloadResult downloadSubtitle(int fileId) {
        ensureAuthenticated();

        log.info("Requesting download link for file_id: {}", fileId);

        // First, get the download link
        Map<String, Object> body = Map.of("file_id", fileId);

        try {
            String response = restClient.post()
                    .uri(baseUrl + "/download")
                    .headers(this::addAuthHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode json = jsonMapper.readTree(response);
            String downloadLink = json.path("link").asText();
            String fileName = json.path("file_name").asText();

            if (downloadLink == null || downloadLink.isBlank()) {
                throw new RuntimeException("No download link in response");
            }

            log.info("Downloading subtitle '{}' from: {}", fileName, downloadLink);

            // Download the actual subtitle file
            String subtitleContent = restClient.get()
                    .uri(downloadLink)
                    .retrieve()
                    .body(String.class);

            return new DownloadResult(subtitleContent, fileName);
        } catch (Exception e) {
            log.error("Failed to download subtitle from OpenSubtitles", e);
            throw new RuntimeException("OpenSubtitles download failed: " + e.getMessage(), e);
        }
    }

    /**
     * Transform search results to show as Hebrew (since we'll translate them).
     */
    private SubtitleSearchResponse transformToHebrew(SubtitleSearchResponse response) {
        if (response == null || response.data() == null) {
            return response;
        }

        // Create new list with language changed to Hebrew
        List<SubtitleSearchResponse.SubtitleData> hebrewData = response.data().stream()
                .map(data -> {
                    var attrs = data.attributes();
                    // Create new attributes with Hebrew language
                    var hebrewAttrs = new SubtitleSearchResponse.SubtitleAttributes(
                            attrs.subtitleId(),
                            "he", // Change language to Hebrew
                            attrs.downloadCount(),
                            attrs.newDownloadCount(),
                            attrs.hearingImpaired(),
                            attrs.hd(),
                            attrs.fps(),
                            attrs.votes(),
                            attrs.ratings(),
                            attrs.fromTrusted(),
                            attrs.foreignPartsOnly(),
                            attrs.uploadDate(),
                            true, // Mark as AI translated
                            true, // Mark as machine translated
                            attrs.release() + " [Translated]", // Add indicator
                            attrs.comments(),
                            attrs.legacySubtitleId(),
                            attrs.uploader(),
                            attrs.featureDetails(),
                            attrs.url(),
                            attrs.relatedLinks(),
                            attrs.files());
                    return new SubtitleSearchResponse.SubtitleData(data.id(), data.type(), hebrewAttrs);
                })
                .toList();

        return new SubtitleSearchResponse(
                response.totalPages(),
                response.totalCount(),
                response.perPage(),
                response.page(),
                hebrewData);
    }

    private void ensureAuthenticated() {
        if (authToken == null || authToken.isBlank()) {
            login();
        }
    }

    private void addCommonHeaders(HttpHeaders headers) {
        headers.set("User-Agent", "TranslationLayer v1.0");
        headers.set("Accept", "application/json");
        String apiKey = appSettings.getOpenSubtitlesApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("Api-Key", apiKey);
        }
    }

    private void addAuthHeaders(HttpHeaders headers) {
        addCommonHeaders(headers);
        if (authToken != null && !authToken.isBlank()) {
            headers.set("Authorization", "Bearer " + authToken);
        }
    }

    private String encodeParam(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean isAuthenticated() {
        return authToken != null && !authToken.isBlank();
    }
}

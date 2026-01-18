package com.example.translationlayer.service;

import com.example.translationlayer.model.SubtitleEntry;
import com.example.translationlayer.model.SubtitleSearchResponse;
import com.example.translationlayer.model.SubtitleSearchResponse.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for managing subtitle files - storage, search, translation, and
 * OpenSubtitles proxy.
 */
@Service
public class SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    private final SubtitleParser parser;
    private final TranslationService translationService;
    private final OpenSubtitlesClient openSubtitlesClient;
    private final TranslationProgressTracker progressTracker;

    @Value("${translation.subtitle-directory:./subtitles}")
    private String subtitleDirectory;

    @Value("${translation.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${translation.cache.directory:./subtitle-cache}")
    private String cacheDirectory;

    // In-memory index of available subtitles
    private final Map<Integer, SubtitleInfo> subtitleIndex = new ConcurrentHashMap<>();
    private int nextFileId = 1;

    public SubtitleService(SubtitleParser parser, TranslationService translationService,
            OpenSubtitlesClient openSubtitlesClient, TranslationProgressTracker progressTracker) {
        this.parser = parser;
        this.translationService = translationService;
        this.openSubtitlesClient = openSubtitlesClient;
        this.progressTracker = progressTracker;
    }

    @PostConstruct
    public void init() throws IOException {
        // Create directories if they don't exist
        Files.createDirectories(Path.of(subtitleDirectory));
        Files.createDirectories(Path.of(cacheDirectory));

        // Index existing subtitle files
        indexExistingSubtitles();

        log.info("SubtitleService initialized with {} indexed subtitles", subtitleIndex.size());
    }

    // ==================== PROXY MODE METHODS ====================

    /**
     * Proxy search to OpenSubtitles - returns English subtitles marked as Hebrew.
     */
    public SubtitleSearchResponse proxySearchSubtitles(String query, String imdbId, String tmdbId,
            String movieHash, Integer page) {
        log.info("Proxy search: query={}, imdb={}, tmdb={}, hash={}", query, imdbId, tmdbId, movieHash);
        return openSubtitlesClient.searchSubtitles(query, imdbId, tmdbId, movieHash, page);
    }

    /**
     * Download from OpenSubtitles, translate, and cache.
     * Returns the translated Hebrew subtitle content.
     */
    public String proxyDownloadAndTranslate(int fileId, String format, String fileName) throws IOException {
        log.info("Proxy download and translate for file_id: {}", fileId);

        // Cache structure: cacheDirectory/{fileId}/original.srt, translated.srt, and
        // metadata.json
        Path cacheDir = Path.of(cacheDirectory, String.valueOf(fileId));
        Path originalPath = cacheDir.resolve("original.srt");
        Path translatedPath = cacheDir.resolve("translated_he.srt");
        Path metadataPath = cacheDir.resolve("metadata.json");

        String englishContent;
        String translatedContent;

        // Check if we have a cached translation
        if (cacheEnabled && Files.exists(translatedPath) && Files.exists(originalPath)) {
            log.info("Returning cached translation for OpenSubtitles file_id: {}", fileId);
            translatedContent = Files.readString(translatedPath, StandardCharsets.UTF_8);

            // Convert format if needed
            if ("vtt".equalsIgnoreCase(format)) {
                List<SubtitleEntry> entries = parser.parseSrt(translatedContent);
                return parser.generateVtt(entries);
            }
            return translatedContent;
        }

        // Download English subtitle from OpenSubtitles
        log.info("Downloading English subtitle from OpenSubtitles for file_id: {}", fileId);
        OpenSubtitlesClient.DownloadResult downloadResult = openSubtitlesClient.downloadSubtitle(fileId);
        englishContent = downloadResult.content();

        // Use the actual filename from OpenSubtitles
        String actualFileName = downloadResult.fileName();
        String displayName = (actualFileName != null && !actualFileName.isBlank())
                ? actualFileName
                : (fileName != null && !fileName.isBlank()) ? fileName : "subtitle_" + fileId + ".srt";

        // Parse subtitles
        List<SubtitleEntry> entries = parser.parse(englishContent);
        log.info("Parsed {} subtitle entries from '{}'", entries.size(), displayName);

        // Track translation progress
        progressTracker.startTranslation(String.valueOf(fileId), displayName, entries.size());

        try {
            // Translate with progress updates
            log.info("Translating {} entries...", entries.size());
            String fileIdStr = String.valueOf(fileId);
            List<SubtitleEntry> translatedEntries = translationService.translateSubtitles(entries,
                    completed -> progressTracker.updateProgress(fileIdStr, completed));

            // Generate translated content
            translatedContent = parser.generateSrt(translatedEntries);

            // Cache both original and translated with metadata
            if (cacheEnabled) {
                Files.createDirectories(cacheDir);
                Files.writeString(originalPath, englishContent, StandardCharsets.UTF_8);
                Files.writeString(translatedPath, translatedContent, StandardCharsets.UTF_8);

                // Save metadata with filename
                String metadataJson = String.format("{\"fileName\":\"%s\",\"fileId\":%d}",
                        displayName.replace("\\", "\\\\").replace("\"", "\\\""), fileId);
                Files.writeString(metadataPath, metadataJson, StandardCharsets.UTF_8);

                log.info("Cached original and translated subtitles with metadata for file_id: {}", fileId);
            }

            // Convert format if needed
            if ("vtt".equalsIgnoreCase(format)) {
                return parser.generateVtt(translatedEntries);
            }

            return translatedContent;
        } finally {
            progressTracker.completeTranslation(String.valueOf(fileId));
        }
    }

    /**
     * Overload for backward compatibility.
     */
    public String proxyDownloadAndTranslate(int fileId, String format) throws IOException {
        return proxyDownloadAndTranslate(fileId, format, null);
    }

    /**
     * Check if a translated subtitle is already cached.
     */
    public boolean isTranslationCached(int fileId) {
        Path translatedPath = Path.of(cacheDirectory, String.valueOf(fileId), "translated_he.srt");
        return cacheEnabled && Files.exists(translatedPath);
    }

    /**
     * Translate raw subtitle content to the specified target language.
     * Used for translating existing subtitles (e.g., from NAS).
     */
    public String translateSubtitleContent(String subtitleContent, String targetLanguage) throws IOException {
        log.info("Translating subtitle content to {}", targetLanguage);

        // Parse the subtitle content
        List<SubtitleEntry> entries = parser.parse(subtitleContent);
        log.info("Parsed {} subtitle entries for translation", entries.size());

        if (entries.isEmpty()) {
            throw new IOException("No subtitle entries found in content");
        }

        // Register with progress tracker using a generated ID
        String progressId = "local_" + System.currentTimeMillis();
        progressTracker.startTranslation(progressId, "Local subtitle", entries.size());

        try {
            // Translate entries with progress callback
            List<SubtitleEntry> translatedEntries = translationService.translateSubtitles(entries,
                    completed -> progressTracker.updateProgress(progressId, completed));

            // Generate output as SRT
            return parser.generateSrt(translatedEntries);
        } finally {
            progressTracker.completeTranslation(progressId);
        }
    }

    // ==================== LOCAL MODE METHODS ====================

    /**
     * Indexes existing subtitle files in the subtitle directory.
     */
    private void indexExistingSubtitles() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of(subtitleDirectory), 2)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".srt") || p.toString().endsWith(".vtt"))
                    .forEach(this::indexSubtitleFile);
        }
    }

    private void indexSubtitleFile(Path path) {
        int fileId = nextFileId++;
        String fileName = path.getFileName().toString();
        String title = extractTitleFromFileName(fileName);

        SubtitleInfo info = new SubtitleInfo(
                fileId,
                path.toString(),
                fileName,
                title,
                "en",
                false);

        subtitleIndex.put(fileId, info);
        log.debug("Indexed subtitle: {} with ID {}", fileName, fileId);
    }

    private String extractTitleFromFileName(String fileName) {
        String title = fileName.replaceAll("\\.(srt|vtt)$", "");
        title = title.replaceAll("[._-]", " ");
        title = title.replaceAll("\\s+", " ");
        return title.trim();
    }

    /**
     * Searches for local subtitles matching the query.
     */
    public SubtitleSearchResponse searchSubtitles(String query, String imdbId, String tmdbId,
            String languages, int page, int perPage) {

        List<SubtitleInfo> results = subtitleIndex.values().stream()
                .filter(info -> matchesQuery(info, query, imdbId, tmdbId))
                .collect(Collectors.toList());

        int totalCount = results.size();
        int totalPages = (int) Math.ceil((double) totalCount / perPage);

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, results.size());
        List<SubtitleInfo> pageResults = start < results.size() ? results.subList(start, end) : Collections.emptyList();

        List<SubtitleData> data = pageResults.stream()
                .map(this::toSubtitleData)
                .collect(Collectors.toList());

        return new SubtitleSearchResponse(totalPages, totalCount, perPage, page, data);
    }

    private boolean matchesQuery(SubtitleInfo info, String query, String imdbId, String tmdbId) {
        if (query == null || query.isBlank()) {
            return true;
        }

        String lowerQuery = query.toLowerCase();
        return info.title().toLowerCase().contains(lowerQuery) ||
                info.fileName().toLowerCase().contains(lowerQuery);
    }

    private SubtitleData toSubtitleData(SubtitleInfo info) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

        Uploader uploader = new Uploader(1, "Translation Layer", "translator");
        FeatureDetails featureDetails = new FeatureDetails(
                info.fileId(), "movie", 2024, info.title(), info.title(), 0, 0);

        List<SubtitleFile> files = List.of(
                new SubtitleFile(info.fileId(), 1, info.fileName()));

        String subtitleUrl = String.format("http://localhost:8080/api/v1/download/%d/subtitle.srt", info.fileId());

        SubtitleAttributes attributes = new SubtitleAttributes(
                String.valueOf(info.fileId()),
                "he",
                0, 0, false, true, 23.976, 0, 10.0,
                true, false, now, true, true,
                info.title(), "", info.fileId(),
                uploader, featureDetails, subtitleUrl, Collections.emptyList(), files);

        return new SubtitleData(
                String.valueOf(info.fileId()),
                "subtitle",
                attributes);
    }

    /**
     * Gets the translated subtitle content for local files.
     */
    public String getTranslatedSubtitle(int fileId, String format) throws IOException {
        SubtitleInfo info = subtitleIndex.get(fileId);
        if (info == null) {
            throw new IllegalArgumentException("Subtitle not found: " + fileId);
        }

        String cacheKey = getCacheKey(fileId, format);
        Path cachePath = Path.of(cacheDirectory, cacheKey);

        if (cacheEnabled && Files.exists(cachePath)) {
            log.info("Returning cached translation for file ID {}", fileId);
            return Files.readString(cachePath, StandardCharsets.UTF_8);
        }

        String originalContent = Files.readString(Path.of(info.filePath()), StandardCharsets.UTF_8);
        List<SubtitleEntry> entries = parser.parse(originalContent);

        log.info("Translating {} subtitle entries for file ID {}", entries.size(), fileId);
        List<SubtitleEntry> translatedEntries = translationService.translateSubtitles(entries);

        String outputFormat = format != null ? format.toLowerCase() : "srt";
        String translatedContent = switch (outputFormat) {
            case "vtt" -> parser.generateVtt(translatedEntries);
            default -> parser.generateSrt(translatedEntries);
        };

        if (cacheEnabled) {
            Files.writeString(cachePath, translatedContent, StandardCharsets.UTF_8);
            log.info("Cached translation for file ID {}", fileId);
        }

        return translatedContent;
    }

    private String getCacheKey(int fileId, String format) {
        return String.format("%d_he.%s", fileId, format != null ? format : "srt");
    }

    /**
     * Adds a new subtitle file for translation.
     */
    public int addSubtitle(String fileName, String content) throws IOException {
        Path filePath = Path.of(subtitleDirectory, fileName);
        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        int fileId = nextFileId++;
        String title = extractTitleFromFileName(fileName);

        SubtitleInfo info = new SubtitleInfo(
                fileId, filePath.toString(), fileName, title, "en", false);

        subtitleIndex.put(fileId, info);
        log.info("Added new subtitle: {} with ID {}", fileName, fileId);

        return fileId;
    }

    public Optional<SubtitleInfo> getSubtitleInfo(int fileId) {
        return Optional.ofNullable(subtitleIndex.get(fileId));
    }

    public record SubtitleInfo(
            int fileId,
            String filePath,
            String fileName,
            String title,
            String sourceLanguage,
            boolean translated) {
    }
}

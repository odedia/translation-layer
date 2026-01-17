package com.example.translationlayer.controller;

import com.example.translationlayer.model.DownloadRequest;
import com.example.translationlayer.model.DownloadResponse;
import com.example.translationlayer.model.SubtitleSearchResponse;
import com.example.translationlayer.service.SubtitleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Subtitle search and download controller matching OpenSubtitles API.
 * Operates in PROXY MODE: fetches English from OpenSubtitles, translates to
 * Hebrew.
 */
@RestController
@RequestMapping("/api/v1")
public class SubtitleController {

        private static final Logger log = LoggerFactory.getLogger(SubtitleController.class);

        private final SubtitleService subtitleService;

        public SubtitleController(SubtitleService subtitleService) {
                this.subtitleService = subtitleService;
        }

        /**
         * GET /api/v1/subtitles - Search for subtitles.
         * PROXY MODE: Searches OpenSubtitles for English subtitles, returns them as
         * Hebrew options.
         */
        @GetMapping("/subtitles")
        public ResponseEntity<SubtitleSearchResponse> searchSubtitles(
                        @RequestParam(required = false) String query,
                        @RequestParam(required = false) String imdb_id,
                        @RequestParam(required = false) String tmdb_id,
                        @RequestParam(required = false) String parent_imdb_id,
                        @RequestParam(required = false) String parent_tmdb_id,
                        @RequestParam(required = false, defaultValue = "he") String languages,
                        @RequestParam(required = false) String moviehash,
                        @RequestParam(required = false) String type,
                        @RequestParam(required = false, defaultValue = "1") int page,
                        @RequestHeader(value = "Api-Key", required = false) String apiKey,
                        @RequestHeader(value = "Authorization", required = false) String authorization) {

                log.info("Subtitle search (PROXY MODE) - query: {}, imdb_id: {}, tmdb_id: {}, moviehash: {}",
                                query, imdb_id, tmdb_id, moviehash);

                try {
                        // Use IMDB ID from parent if not directly provided (for TV episodes)
                        String effectiveImdbId = imdb_id != null ? imdb_id : parent_imdb_id;
                        String effectiveTmdbId = tmdb_id != null ? tmdb_id : parent_tmdb_id;

                        // Proxy to OpenSubtitles - fetches English, marks as Hebrew
                        SubtitleSearchResponse response = subtitleService.proxySearchSubtitles(
                                        query, effectiveImdbId, effectiveTmdbId, moviehash, page);

                        log.info("Found {} subtitle options", response.totalCount());
                        return ResponseEntity.ok(response);
                } catch (Exception e) {
                        log.error("Proxy search failed", e);
                        // Return empty response on error
                        return ResponseEntity
                                        .ok(new SubtitleSearchResponse(0, 0, 20, 1, java.util.Collections.emptyList()));
                }
        }

        /**
         * POST /api/v1/download - Request subtitle download/translation.
         * Returns a download link that will trigger translation on access.
         */
        @PostMapping("/download")
        public ResponseEntity<?> download(
                        @RequestBody DownloadRequest request,
                        @RequestHeader(value = "Api-Key", required = false) String apiKey,
                        @RequestHeader(value = "Authorization", required = false) String authorization) {

                log.info("Download request (PROXY MODE) for file_id: {}", request.fileId());

                try {
                        // Generate download link - actual translation happens when this link is
                        // accessed
                        String downloadLink = String.format(
                                        "http://localhost:8080/api/v1/download/%d/subtitle.%s",
                                        request.fileId(),
                                        request.subFormat() != null ? request.subFormat() : "srt");

                        String resetTime = LocalDateTime.now().plusDays(1)
                                        .format(DateTimeFormatter.ISO_DATE_TIME);

                        // Check if already cached
                        boolean cached = subtitleService.isTranslationCached(request.fileId());
                        String message = cached ? "Hebrew translation ready (cached)"
                                        : "Hebrew translation will be generated on download";

                        DownloadResponse response = new DownloadResponse(
                                        downloadLink,
                                        String.format("subtitle_%d_hebrew.%s", request.fileId(),
                                                        request.subFormat() != null ? request.subFormat() : "srt"),
                                        1,
                                        999,
                                        message,
                                        resetTime,
                                        resetTime);

                        return ResponseEntity.ok(response);
                } catch (Exception e) {
                        log.error("Download request failed for file_id: {}", request.fileId(), e);
                        return ResponseEntity.badRequest().body(Map.of(
                                        "message", "Download request failed: " + e.getMessage(),
                                        "status", 400));
                }
        }

        /**
         * GET /api/v1/download/{fileId}/{fileName} - Actual subtitle file download.
         * PROXY MODE: Downloads English from OpenSubtitles, translates to Hebrew,
         * returns Hebrew.
         */
        @GetMapping("/download/{fileId}/{fileName}")
        public ResponseEntity<String> downloadFile(
                        @PathVariable int fileId,
                        @PathVariable String fileName,
                        @RequestHeader(value = "Api-Key", required = false) String apiKey,
                        @RequestHeader(value = "Authorization", required = false) String authorization) {

                log.info("File download (PROXY MODE) for file_id: {}", fileId);

                try {
                        // Determine format from filename
                        String format = fileName.endsWith(".vtt") ? "vtt" : "srt";

                        // This will: download from OpenSubtitles -> translate -> cache -> return Hebrew
                        // Pass filename for metadata storage
                        String content = subtitleService.proxyDownloadAndTranslate(fileId, format, fileName);

                        String contentType = format.equals("vtt") ? "text/vtt; charset=utf-8"
                                        : "application/x-subrip; charset=utf-8";

                        return ResponseEntity.ok()
                                        .header("Content-Type", contentType)
                                        .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                                        .body(content);
                } catch (IOException e) {
                        log.error("File download failed for file_id: {}", fileId, e);
                        return ResponseEntity.internalServerError()
                                        .body("Translation failed: " + e.getMessage());
                }
        }

        /**
         * POST /api/v1/upload - Upload a new subtitle file for translation.
         * This is for LOCAL MODE - manual subtitle upload.
         */
        @PostMapping("/upload")
        public ResponseEntity<?> uploadSubtitle(
                        @RequestParam("file") MultipartFile file,
                        @RequestHeader(value = "Api-Key", required = false) String apiKey,
                        @RequestHeader(value = "Authorization", required = false) String authorization) {

                log.info("Uploading subtitle file: {}", file.getOriginalFilename());

                try {
                        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
                        int fileId = subtitleService.addSubtitle(file.getOriginalFilename(), content);

                        return ResponseEntity.ok(Map.of(
                                        "message", "Subtitle uploaded successfully",
                                        "file_id", fileId,
                                        "file_name", file.getOriginalFilename(),
                                        "status", 200));
                } catch (IOException e) {
                        log.error("Upload failed for file: {}", file.getOriginalFilename(), e);
                        return ResponseEntity.badRequest().body(Map.of(
                                        "message", "Upload failed: " + e.getMessage(),
                                        "status", 400));
                }
        }
}

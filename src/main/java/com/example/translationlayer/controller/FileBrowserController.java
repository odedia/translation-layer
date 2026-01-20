package com.example.translationlayer.controller;

import com.example.translationlayer.config.AppSettings;
import com.example.translationlayer.config.LanguageConfig;
import com.example.translationlayer.config.SmbConfig;
import com.example.translationlayer.model.SubtitleSearchResponse;
import com.example.translationlayer.service.FfmpegService;
import com.example.translationlayer.service.FileSystemService;
import com.example.translationlayer.service.LocalFileService;
import com.example.translationlayer.service.NasDiscoveryService;
import com.example.translationlayer.service.OpenSubtitlesClient;
import com.example.translationlayer.service.SmbService;
import com.example.translationlayer.service.SubtitleService;
import com.example.translationlayer.service.TranslationProgressTracker;
import com.example.translationlayer.service.BatchTranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Controller for browsing NAS files and downloading/translating subtitles.
 * Mobile-friendly interface for use with Infuse on Apple TV.
 */
@Controller
public class FileBrowserController {

        private static final Logger log = LoggerFactory.getLogger(FileBrowserController.class);

        private final SmbConfig smbConfig;
        private final SmbService smbService;
        private final LocalFileService localFileService;
        private final AppSettings appSettings;
        private final LanguageConfig languageConfig;
        private final OpenSubtitlesClient openSubtitlesClient;
        private final SubtitleService subtitleService;
        private final NasDiscoveryService nasDiscoveryService;
        private final FfmpegService ffmpegService;
        private final TranslationProgressTracker progressTracker;
        private final BatchTranslationService batchTranslationService;

        public FileBrowserController(SmbConfig smbConfig, SmbService smbService,
                        LocalFileService localFileService, AppSettings appSettings,
                        LanguageConfig languageConfig, OpenSubtitlesClient openSubtitlesClient,
                        SubtitleService subtitleService, NasDiscoveryService nasDiscoveryService,
                        FfmpegService ffmpegService, TranslationProgressTracker progressTracker,
                        BatchTranslationService batchTranslationService) {
                this.smbConfig = smbConfig;
                this.smbService = smbService;
                this.localFileService = localFileService;
                this.appSettings = appSettings;
                this.languageConfig = languageConfig;
                this.openSubtitlesClient = openSubtitlesClient;
                this.subtitleService = subtitleService;
                this.nasDiscoveryService = nasDiscoveryService;
                this.ffmpegService = ffmpegService;
                this.progressTracker = progressTracker;
                this.batchTranslationService = batchTranslationService;
        }

        /**
         * Get the appropriate file service based on current browse mode.
         */
        private FileSystemService getFileService() {
                return appSettings.isLocalMode() ? localFileService : smbService;
        }

        /**
         * Check if current file service is configured.
         */
        private boolean isFileServiceConfigured() {
                return appSettings.isLocalMode() ? localFileService.isConfigured() : smbConfig.isConfigured();
        }

        // ==================== REST API Endpoints ====================

        @GetMapping("/api/browse")
        @ResponseBody
        public ResponseEntity<?> listDirectory(@RequestParam(defaultValue = "") String path) {
                try {
                        List<SmbService.FileEntry> entries = getFileService().listDirectory(path);
                        return ResponseEntity.ok(entries);
                } catch (IOException e) {
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
        }

        @GetMapping("/api/browse/search")
        @ResponseBody
        public ResponseEntity<?> searchSubtitles(@RequestParam String videoPath) {
                try {
                        String title = smbService.extractVideoTitle(videoPath);
                        log.info("Searching subtitles for: {} (extracted: {})", videoPath, title);

                        SubtitleSearchResponse response = openSubtitlesClient.searchSubtitles(title, null, null, null,
                                        1);
                        return ResponseEntity.ok(response);
                } catch (Exception e) {
                        log.error("Subtitle search failed", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
        }

        @GetMapping("/api/browse/search-manual")
        @ResponseBody
        public ResponseEntity<?> searchSubtitlesManual(@RequestParam String query) {
                try {
                        log.info("Manual subtitle search: {}", query);
                        SubtitleSearchResponse response = openSubtitlesClient.searchSubtitles(query, null, null, null,
                                        1);
                        return ResponseEntity.ok(response);
                } catch (Exception e) {
                        log.error("Manual subtitle search failed", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
        }

        @PostMapping("/api/browse/translate")
        @ResponseBody
        public ResponseEntity<?> translateAndSave(@RequestBody TranslateRequest request) {
                try {
                        log.info("Translating subtitle {} for video {}", request.fileId(), request.videoPath());

                        // Download and translate the subtitle
                        String translatedContent = subtitleService.proxyDownloadAndTranslate(
                                        request.fileId(),
                                        "srt",
                                        request.fileName());

                        // Write to NAS next to video
                        String languageCode = languageConfig.getLanguageCode();
                        String subtitlePath = smbService.writeSubtitle(request.videoPath(), translatedContent,
                                        languageCode);

                        log.info("Subtitle saved to NAS: {}", subtitlePath);
                        return ResponseEntity.ok(Map.of(
                                        "success", true,
                                        "path", subtitlePath,
                                        "language", languageConfig.getTargetLanguage()));
                } catch (Exception e) {
                        log.error("Translation failed", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
        }

        @GetMapping("/api/browse/settings")
        @ResponseBody
        public Map<String, Object> getSettings() {
                return Map.of(
                                "browseMode", appSettings.getBrowseMode(),
                                "localRootPath", appSettings.getLocalRootPath(),
                                "smb", smbConfig.getSettings());
        }

        @PostMapping("/api/browse/settings")
        @ResponseBody
        public ResponseEntity<?> updateSettings(@RequestBody SmbSettingsRequest request) {
                smbConfig.updateSettings(
                                request.host(),
                                request.share(),
                                request.username(),
                                request.password(),
                                request.domain());
                return ResponseEntity.ok(Map.of("success", true));
        }

        @PostMapping("/api/browse/mode")
        @ResponseBody
        public ResponseEntity<?> updateBrowseMode(@RequestBody Map<String, String> request) {
                String mode = request.get("browseMode");
                String localPath = request.get("localRootPath");

                if (mode != null) {
                        appSettings.setBrowseMode(mode);
                }
                if (localPath != null) {
                        appSettings.setLocalRootPath(localPath);
                }
                appSettings.saveSettings();

                return ResponseEntity.ok(Map.of("success", true, "browseMode", appSettings.getBrowseMode()));
        }

        @PostMapping("/api/browse/test")
        @ResponseBody
        public ResponseEntity<?> testConnection(@RequestBody(required = false) Map<String, String> request) {
                String error;

                // Check if testing a local path that hasn't been saved yet
                if (request != null && request.containsKey("localPath")) {
                        String localPath = request.get("localPath");
                        if (localPath == null || localPath.isBlank()) {
                                error = "Local root path not provided";
                        } else {
                                java.nio.file.Path path = java.nio.file.Path.of(localPath);
                                if (!java.nio.file.Files.exists(path)) {
                                        error = "Path does not exist: " + localPath;
                                } else if (!java.nio.file.Files.isDirectory(path)) {
                                        error = "Path is not a directory: " + localPath;
                                } else if (!java.nio.file.Files.isReadable(path)) {
                                        error = "Path is not readable: " + localPath;
                                } else {
                                        error = null; // Success
                                }
                        }
                } else {
                        // Use the current file service's saved settings
                        error = getFileService().testConnection();
                }

                if (error == null) {
                        return ResponseEntity.ok(Map.of("success", true));
                } else {
                        return ResponseEntity.ok(Map.of("success", false, "error", error));
                }
        }

        @GetMapping("/api/browse/discover")
        @ResponseBody
        public ResponseEntity<?> discoverNas() {
                if (!nasDiscoveryService.isReady()) {
                        return ResponseEntity.ok(Map.of(
                                        "devices", List.of(),
                                        "message", "Discovery initializing, please wait..."));
                }
                nasDiscoveryService.refresh();
                var devices = nasDiscoveryService.getDiscoveredDevices();
                return ResponseEntity.ok(Map.of("devices", devices));
        }

        @GetMapping("/api/browse/embedded-tracks")
        @ResponseBody
        public ResponseEntity<?> getEmbeddedTracks(@RequestParam String videoPath) {
                if (!ffmpegService.isAvailable()) {
                        return ResponseEntity.ok(Map.of(
                                        "available", false,
                                        "message", "FFmpeg not installed on server"));
                }

                Path tempFile = null;
                try {
                        log.info("Checking embedded subtitles in: {}", videoPath);
                        // Download only first 20MB for metadata analysis (much faster than full file)
                        tempFile = smbService.downloadHeaderToTempFile(videoPath);
                        var tracks = ffmpegService.getSubtitleTracks(tempFile);

                        // Return all tracks - let user choose. English tracks will be highlighted in
                        // UI.
                        return ResponseEntity.ok(Map.of(
                                        "available", true,
                                        "tracks", tracks));

                } catch (Exception e) {
                        log.error("Failed to get embedded tracks", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                } finally {
                        if (tempFile != null) {
                                try {
                                        Files.deleteIfExists(tempFile);
                                } catch (Exception ignored) {
                                }
                        }
                }
        }

        @PostMapping("/api/browse/translate-embedded")
        @ResponseBody
        public ResponseEntity<?> translateEmbeddedSubtitle(@RequestBody TranslateEmbeddedRequest request) {
                Path tempFile = null;
                try {
                        log.info("Extracting embedded subtitle track {} from: {}", request.trackIndex(),
                                        request.videoPath());

                        // Generate a cache key from video filename
                        String videoFileName = Path.of(request.videoPath()).getFileName().toString();
                        String cacheId = "embedded_" + videoFileName.replaceAll("[^a-zA-Z0-9._-]", "_") + "_track"
                                        + request.trackIndex();

                        // Check if already cached
                        Path cacheDir = Path.of(System.getProperty("user.home"), ".subtitle-cache", cacheId);
                        Path translatedPath = cacheDir.resolve("translated_he.srt");

                        String translatedContent;
                        if (Files.exists(translatedPath)) {
                                log.info("Returning cached embedded translation for: {}", cacheId);
                                translatedContent = Files.readString(translatedPath);
                        } else {
                                // Download video to temp file
                                tempFile = smbService.downloadToTempFile(request.videoPath());

                                // Extract subtitle track
                                String subtitleContent = ffmpegService.extractSubtitle(tempFile, request.trackIndex());

                                // Translate the content (uses progress tracker internally)
                                translatedContent = subtitleService.translateSubtitleContent(
                                                subtitleContent,
                                                languageConfig.getTargetLanguage());

                                // Cache both original and translated
                                Files.createDirectories(cacheDir);
                                Files.writeString(cacheDir.resolve("original.srt"), subtitleContent);
                                Files.writeString(translatedPath, translatedContent);
                                // Save metadata
                                String metadataJson = String.format(
                                                "{\"fileName\":\"%s\",\"videoPath\":\"%s\",\"trackIndex\":%d}",
                                                videoFileName.replace("\\", "\\\\").replace("\"", "\\\""),
                                                request.videoPath().replace("\\", "\\\\").replace("\"", "\\\""),
                                                request.trackIndex());
                                Files.writeString(cacheDir.resolve("metadata.json"), metadataJson);
                                log.info("Cached embedded subtitle translation: {}", cacheId);
                        }

                        // Save to NAS next to video
                        String languageCode = languageConfig.getLanguageCode();
                        String outputPath = smbService.writeSubtitle(request.videoPath(), translatedContent,
                                        languageCode);

                        log.info("Embedded subtitle translated and saved to: {}", outputPath);
                        return ResponseEntity.ok(Map.of(
                                        "success", true,
                                        "path", outputPath,
                                        "language", languageConfig.getTargetLanguage()));

                } catch (Exception e) {
                        log.error("Embedded subtitle translation failed", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                } finally {
                        if (tempFile != null) {
                                try {
                                        Files.deleteIfExists(tempFile);
                                } catch (Exception ignored) {
                                }
                        }
                }
        }

        @GetMapping("/api/browse/progress")
        @ResponseBody
        public ResponseEntity<?> getProgress() {
                var translations = progressTracker.getActiveTranslations();
                var batchProgress = batchTranslationService.getBatchProgress();
                return ResponseEntity.ok(Map.of(
                                "translations", translations,
                                "batch", batchProgress != null ? batchProgress : Map.of()));
        }

        // ==================== Batch Translation Endpoints ====================

        @PostMapping("/api/browse/batch-analyze")
        @ResponseBody
        public ResponseEntity<?> batchAnalyze(@RequestBody Map<String, String> request) {
                try {
                        String folderPath = request.get("folderPath");
                        if (folderPath == null || folderPath.isBlank()) {
                                return ResponseEntity.badRequest().body(Map.of("error", "folderPath is required"));
                        }
                        log.info("Starting batch analysis for folder: {}", folderPath);
                        var result = batchTranslationService.analyzeFolder(folderPath);
                        return ResponseEntity.ok(Map.of(
                                        "success", true,
                                        "totalVideos", result.totalVideos(),
                                        "videos", result.videos()));
                } catch (Exception e) {
                        log.error("Batch analysis failed", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
        }

        @PostMapping("/api/browse/batch-start")
        @ResponseBody
        public ResponseEntity<?> batchStart() {
                try {
                        String targetLanguage = languageConfig.getTargetLanguage();
                        log.info("Starting batch translation to: {}", targetLanguage);
                        batchTranslationService.startBatchTranslation(targetLanguage);
                        return ResponseEntity.ok(Map.of("success", true, "message", "Batch translation started"));
                } catch (Exception e) {
                        log.error("Failed to start batch translation", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
        }

        @GetMapping("/api/browse/batch-progress")
        @ResponseBody
        public ResponseEntity<?> batchProgress() {
                var progress = batchTranslationService.getBatchProgress();
                if (progress == null) {
                        return ResponseEntity.ok(Map.of("active", false));
                }
                return ResponseEntity.ok(Map.of(
                                "active", true,
                                "batchId", progress.batchId(),
                                "folderPath", progress.folderPath(),
                                "totalVideos", progress.totalVideos(),
                                "completedVideos", progress.completedVideos(),
                                "currentVideo", progress.currentVideo() != null ? progress.currentVideo() : "",
                                "progressPercent", progress.progressPercent(),
                                "status", progress.status().name(),
                                "error", progress.error() != null ? progress.error() : ""));
        }

        @PostMapping("/api/browse/batch-cancel")
        @ResponseBody
        public ResponseEntity<?> batchCancel() {
                batchTranslationService.cancelBatch();
                return ResponseEntity.ok(Map.of("success", true, "message", "Batch cancelled"));
        }

        @PostMapping("/api/browse/translate-local")
        @ResponseBody
        public ResponseEntity<?> translateLocalSubtitle(@RequestBody TranslateLocalRequest request) {
                try {
                        log.info("Translating local subtitle: {}", request.subtitlePath());

                        // Read subtitle content from NAS
                        String originalContent = smbService.readSubtitleContent(request.subtitlePath());

                        // Translate the content
                        String translatedContent = subtitleService.translateSubtitleContent(
                                        originalContent,
                                        languageConfig.getTargetLanguage());

                        // Generate output path (replace language code or add it)
                        String languageCode = languageConfig.getLanguageCode();
                        String outputPath = generateTranslatedPath(request.subtitlePath(), languageCode);

                        // Write to NAS
                        smbService.writeSubtitleDirect(outputPath, translatedContent);

                        log.info("Translated subtitle saved to: {}", outputPath);
                        return ResponseEntity.ok(Map.of(
                                        "success", true,
                                        "path", outputPath,
                                        "language", languageConfig.getTargetLanguage()));
                } catch (Exception e) {
                        log.error("Local translation failed", e);
                        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
                }
        }

        private String generateTranslatedPath(String originalPath, String languageCode) {
                // Remove existing language code and extension, add new language code
                String base = originalPath;
                String ext = ".srt";

                // Find and remove extension
                int extIdx = originalPath.lastIndexOf('.');
                if (extIdx > 0) {
                        ext = originalPath.substring(extIdx);
                        base = originalPath.substring(0, extIdx);
                }

                // Check if there's already a language code (e.g., .en, .eng, .hebrew)
                int langIdx = base.lastIndexOf('.');
                if (langIdx > 0) {
                        String possibleLang = base.substring(langIdx + 1).toLowerCase();
                        // Common language indicators
                        if (possibleLang.length() <= 7 && possibleLang.matches("[a-z]+")) {
                                base = base.substring(0, langIdx);
                        }
                }

                return base + "." + languageCode + ext;
        }

        // ==================== Web UI ====================

        @GetMapping("/browse")
        @ResponseBody
        public String browsePage() {
                return generateBrowseHtml();
        }

        private String generateBrowseHtml() {
                String currentLang = languageConfig.getTargetLanguage();
                boolean isConfigured = isFileServiceConfigured();

                StringBuilder html = new StringBuilder();
                html.append("<!DOCTYPE html><html><head>");
                html.append("<title>Translation Layer - File Browser</title>");
                html.append(
                                "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>");
                html.append("<meta name='apple-mobile-web-app-capable' content='yes'>");
                html.append("<style>");

                // Base styles (same as status page)
                html.append("*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}");
                html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;");
                html.append(
                                "background:linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);color:#eee;margin:0;padding:16px;min-height:100vh}");
                html.append(".container{max-width:800px;margin:0 auto}");
                html.append("h1{color:#00d9ff;text-align:center;margin:0 0 6px 0;font-size:1.5em}");
                html.append(".nav{text-align:center;margin-bottom:16px}");
                html.append(".nav a{color:#00d9ff;text-decoration:none;font-size:0.9em}");

                // Card styles
                html.append(
                                ".card{background:rgba(255,255,255,0.1);border-radius:12px;padding:16px;margin-bottom:16px;backdrop-filter:blur(10px)}");
                html.append(".card h2{margin:0 0 12px 0;color:#00d9ff;font-size:1.1em}");

                // Form styles
                html.append(".form-group{margin-bottom:12px}");
                html.append(".form-group label{display:block;margin-bottom:4px;color:#aaa;font-size:0.85em}");
                html.append(".form-group input{width:100%;padding:12px;border:1px solid #444;border-radius:8px;");
                html.append("background:#1a1a2e;color:#eee;font-size:16px}");
                html.append(".form-group input:focus{outline:none;border-color:#00d9ff}");

                // Button styles
                html.append(".btn{display:inline-block;padding:14px 20px;border-radius:8px;font-weight:bold;");
                html.append("border:none;cursor:pointer;font-size:16px;width:100%;text-align:center;margin-top:8px}");
                html.append(".btn-primary{background:#00d9ff;color:#1a1a2e}");
                html.append(".btn-secondary{background:rgba(255,255,255,0.2);color:#eee}");
                html.append(".btn:disabled{opacity:0.5}");

                // File list styles
                html.append(".breadcrumb{display:flex;flex-wrap:wrap;gap:4px;margin-bottom:12px;font-size:0.85em}");
                html.append(
                                ".breadcrumb a{color:#00d9ff;text-decoration:none;padding:4px 8px;background:rgba(0,217,255,0.2);border-radius:4px}");
                html.append(".file-list{list-style:none;padding:0;margin:0}");
                html.append(
                                ".file-item{display:flex;align-items:center;padding:14px;border-bottom:1px solid rgba(255,255,255,0.1);");
                html.append("cursor:pointer;transition:background 0.2s}");
                html.append(".file-item:hover,.file-item:active{background:rgba(0,217,255,0.1)}");
                html.append(".file-icon{font-size:1.5em;margin-right:12px;min-width:30px;text-align:center}");
                html.append(".file-name{flex:1;word-break:break-word}");
                html.append(".file-badge{font-size:0.75em;padding:2px 8px;border-radius:4px;margin-left:8px}");
                html.append(".badge-sub{background:#00ff88;color:#1a1a2e}");

                // Subtitle search styles
                html.append(".subtitle-list{list-style:none;padding:0;margin:0}");
                html.append(".subtitle-item{padding:14px;border-bottom:1px solid rgba(255,255,255,0.1)}");
                html.append(".subtitle-name{font-weight:bold;margin-bottom:4px;word-break:break-word}");
                html.append(".subtitle-meta{font-size:0.8em;color:#888;margin-bottom:8px}");
                html.append(".subtitle-btn{padding:10px 16px;font-size:14px;width:auto}");

                // Progress/status styles
                html.append(".progress-bar{background:#333;border-radius:4px;height:24px;overflow:hidden;margin:12px 0}");
                html.append(
                                ".progress-fill{background:linear-gradient(90deg,#00d9ff,#00ff88);height:100%;transition:width 0.3s}");
                html.append(".status-msg{text-align:center;padding:20px;color:#888}");
                html.append(".success-msg{color:#00ff88;text-align:center;padding:20px}");
                html.append(".error-msg{color:#ff4757;text-align:center;padding:20px}");

                // Language selector
                html.append(".lang-bar{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}");
                html.append(".lang-select{background:rgba(0,217,255,0.2);border:1px solid #00d9ff;color:#00d9ff;");
                html.append("padding:8px 12px;border-radius:6px;font-size:14px}");

                // Loading spinner
                html.append(".spinner{display:inline-block;width:20px;height:20px;border:2px solid #333;");
                html.append("border-top-color:#00d9ff;border-radius:50%;animation:spin 1s linear infinite}");
                html.append("@keyframes spin{to{transform:rotate(360deg)}}");

                html.append("</style></head><body><div class='container'>");

                // Header
                html.append("<h1>📂 File Browser</h1>");
                html.append("<p class='nav'><a href='/status'>← Back to Status</a> | <a href='/settings'>⚙️ Settings</a></p>");

                // Main content area (will be populated by JS)
                html.append("<div id='app'>");

                if (!isConfigured) {
                        // Show settings form
                        html.append("<div class='card'><h2>📂 File Source</h2>");
                        html.append("<div id='settings-form'></div></div>");
                } else {
                        // Show file browser
                        html.append("<div class='card'>");
                        html.append("<div class='lang-bar'>");
                        html.append("<span>Target: <strong>").append(currentLang).append("</strong></span>");
                        String sourceLabel = appSettings.isLocalMode() ? "📂 Source: Local" : "📂 Source: NAS";
                        html.append("<a href='#' onclick='showSettings();return false' style='color:#00d9ff;font-size:0.85em'>")
                                        .append(sourceLabel).append("</a>");
                        html.append("</div>");
                        html.append(
                                        "<div id='browser'><div class='status-msg'><span class='spinner'></span> Loading...</div></div>");
                        html.append("</div>");
                }

                html.append("</div>"); // end app

                // JavaScript
                html.append("<script>");
                html.append("let currentPath='';");
                html.append("let selectedVideo=null;");

                // Initialize
                html.append("document.addEventListener('DOMContentLoaded',function(){");
                if (isConfigured) {
                        html.append("loadDirectory('');");
                } else {
                        html.append("showSettingsForm();");
                }
                html.append("});");

                // Show settings form
                html.append("function showSettings(){");
                html.append(
                                "document.getElementById('app').innerHTML='<div class=\"card\"><h2>\ud83d\udcc2 File Source</h2><div id=\"settings-form\"></div></div>';");
                html.append("showSettingsForm();");
                html.append("}");

                html.append("function showSettingsForm(){");
                html.append("fetch('/api/browse/settings').then(r=>r.json()).then(settings=>{");
                html.append("let s = settings.smb || {};");
                html.append("let mode = settings.browseMode || 'smb';");
                html.append("let localPath = settings.localRootPath || '';");
                html.append("document.getElementById('settings-form').innerHTML=`");
                // Mode toggle tabs
                html.append("<div style='display:flex;gap:0;margin-bottom:16px'>");
                html.append("<button id='tab-local' class='btn ${mode==\"local\"?\"btn-primary\":\"btn-secondary\"}' style='border-radius:6px 0 0 6px' onclick='setMode(\"local\")'>📁 Local</button>");
                html.append("<button id='tab-smb' class='btn ${mode==\"smb\"?\"btn-primary\":\"btn-secondary\"}' style='border-radius:0 6px 6px 0' onclick='setMode(\"smb\")'>🌐 NAS</button>");
                html.append("</div>");
                // Local mode settings
                html.append("<div id='local-settings' style='display:${mode==\"local\"?\"block\":\"none\"}'>");
                html.append("<div class='form-group'><label>Root Path</label><input type='text' id='local-path' value='${localPath}' placeholder='/Volumes/Media or D:\\\\Movies'></div>");
                html.append("<button class='btn btn-secondary' onclick='testConnection()'>Test Access</button>");
                html.append("<button class='btn btn-primary' onclick='saveLocalSettings()'>Save & Browse</button>");
                html.append("</div>");
                // NAS mode settings
                html.append("<div id='smb-settings' style='display:${mode==\"smb\"?\"block\":\"none\"}'>");
                html.append("<button class='btn btn-secondary' onclick='discoverNas()' style='margin-bottom:16px'>🔍 Discover NAS on Network</button>");
                html.append("<div id='discover-results'></div>");
                html.append("<div class='form-group'><label>NAS Host / IP</label><input type='text' id='smb-host' value='${s.host||''}' placeholder='192.168.1.100 or nas.local'></div>");
                html.append("<div class='form-group'><label>Share Name</label><input type='text' id='smb-share' value='${s.share||''}' placeholder='Media'></div>");
                html.append("<div class='form-group'><label>Username (optional)</label><input type='text' id='smb-user' value='${s.username||''}' autocapitalize='off'></div>");
                html.append("<div class='form-group'><label>Password (optional)</label><input type='password' id='smb-pass' placeholder='••••••••'></div>");
                html.append("<div class='form-group'><label>Domain (optional)</label><input type='text' id='smb-domain' value='${s.domain||''}' autocapitalize='off'></div>");
                html.append("<button class='btn btn-secondary' onclick='testConnection()'>Test Connection</button>");
                html.append("<button class='btn btn-primary' onclick='saveSettings()'>Save Settings</button>");
                html.append("</div>");
                html.append("<div id='settings-status'></div>");
                html.append("`;});");
                html.append("}");

                // Set mode function
                html.append("function setMode(mode){");
                html.append("document.getElementById('tab-local').className='btn '+(mode=='local'?'btn-primary':'btn-secondary');");
                html.append("document.getElementById('tab-smb').className='btn '+(mode=='smb'?'btn-primary':'btn-secondary');");
                html.append("document.getElementById('local-settings').style.display=mode=='local'?'block':'none';");
                html.append("document.getElementById('smb-settings').style.display=mode=='smb'?'block':'none';");
                html.append("fetch('/api/browse/mode',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({browseMode:mode})});");
                html.append("}");

                // Save local settings
                html.append("function saveLocalSettings(){");
                html.append("let path=document.getElementById('local-path').value;");
                html.append("fetch('/api/browse/mode',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({browseMode:'local',localRootPath:path})})");
                html.append(".then(r=>r.json()).then(r=>{if(r.success){location.reload();}else{document.getElementById('settings-status').innerHTML='<div class=\"error-msg\">'+r.error+'</div>';}});");
                html.append("}");

                // Discover NAS devices
                html.append("function discoverNas(){");
                html.append("document.getElementById('discover-results').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Scanning network...</div>';");
                html.append("fetch('/api/browse/discover').then(r=>r.json()).then(r=>{");
                html.append("if(r.message){document.getElementById('discover-results').innerHTML='<div class=\"status-msg\">'+r.message+'</div>';return;}");
                html.append("if(!r.devices||r.devices.length===0){document.getElementById('discover-results').innerHTML='<div class=\"status-msg\">No NAS devices found. Try entering details manually.</div>';return;}");
                html.append("let html='<div style=\"margin-bottom:12px;font-size:0.85em;color:#888\">Found devices (tap to select):</div><ul class=\"file-list\">';");
                html.append("r.devices.forEach(d=>{");
                html.append("html+='<li class=\"file-item\" onclick=\"selectNas(\\''+d.host+'\\',\\''+d.name+'\\')\">';");
                html.append("html+='<span class=\"file-icon\">🖥️</span>';");
                html.append("html+='<span class=\"file-name\">'+d.name+'<br><small style=\"color:#888\">'+d.host+'</small></span>';");
                html.append("html+='</li>';});");
                html.append("html+='</ul>';");
                html.append("document.getElementById('discover-results').innerHTML=html;");
                html.append("});");
                html.append("}");

                // Select discovered NAS
                html.append("function selectNas(host,name){");
                html.append("document.getElementById('smb-host').value=host;");
                html.append("document.getElementById('discover-results').innerHTML='<div class=\"success-msg\">✓ Selected: '+name+'</div>';");
                html.append("}");

                // Test connection
                html.append("function testConnection(){");
                html.append("document.getElementById('settings-status').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Testing...</div>';");
                // Check if we're in local mode (local-settings is visible)
                html.append("let localSettings=document.getElementById('local-settings');");
                html.append("let isLocalMode=localSettings && localSettings.style.display!='none';");
                html.append("if(isLocalMode){");
                // Local mode: send the path from input for testing
                html.append("let path=document.getElementById('local-path').value;");
                html.append("fetch('/api/browse/test',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({localPath:path})}).then(r=>r.json()).then(r=>{");
                html.append("if(r.success){document.getElementById('settings-status').innerHTML='<div class=\"success-msg\">✓ Path accessible!</div>';}");
                html.append("else{document.getElementById('settings-status').innerHTML='<div class=\"error-msg\">✗ '+r.error+'</div>';}");
                html.append("});");
                html.append("}else{");
                // NAS mode: save settings first, then test
                html.append("saveSettingsQuiet().then(()=>{");
                html.append("fetch('/api/browse/test',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}).then(r=>r.json()).then(r=>{");
                html.append("if(r.success){document.getElementById('settings-status').innerHTML='<div class=\"success-msg\">✓ Connection successful!</div>';}");
                html.append("else{document.getElementById('settings-status').innerHTML='<div class=\"error-msg\">✗ '+r.error+'</div>';}");
                html.append("});});");
                html.append("}");
                html.append("}");

                // Save settings (quiet, for test)
                html.append("function saveSettingsQuiet(){");
                html.append("return fetch('/api/browse/settings',{method:'POST',headers:{'Content-Type':'application/json'},");
                html.append("body:JSON.stringify({host:document.getElementById('smb-host').value,");
                html.append("share:document.getElementById('smb-share').value,");
                html.append("username:document.getElementById('smb-user').value,");
                html.append("password:document.getElementById('smb-pass').value,");
                html.append("domain:document.getElementById('smb-domain').value})});");
                html.append("}");

                // Save settings
                html.append("function saveSettings(){");
                html.append("saveSettingsQuiet().then(()=>location.reload());");
                html.append("}");

                // Load directory
                html.append("function loadDirectory(path){");
                html.append("currentPath=path;selectedVideo=null;");
                html.append(
                                "document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Loading...</div>';");
                html.append(
                                "fetch('/api/browse?path='+encodeURIComponent(path)).then(r=>r.json()).then(renderDirectory).catch(e=>{");
                html.append("document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+e.message+'</div>';});");
                html.append("}");

                // Render directory
                html.append("function renderDirectory(data){");
                // Handle error response (when API returns {error: ...} instead of array)
                html.append("if(data && data.error){document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+data.error+'</div>';return;}");
                // Ensure entries is an array
                html.append("let entries=Array.isArray(data)?data:[];");
                html.append("let html='';");
                // Breadcrumb
                html.append(
                                "html+='<div class=\"breadcrumb\"><a href=\"#\" onclick=\"loadDirectory(\\'\\');return false\">🏠 Root</a>';");
                html.append("if(currentPath){let parts=currentPath.split('/');let p='';");
                html.append("parts.forEach((part,i)=>{p+=(i>0?'/':'')+part;");
                html.append(
                                "html+='<span>›</span><a href=\"#\" onclick=\"loadDirectory(\\''+p+'\\');return false\">'+part+'</a>';});}");
                html.append("html+='</div>';");
                // Batch translate button (only show in non-root folders)
                html.append("if(currentPath){");
                html.append("html+='<button class=\"btn btn-secondary\" onclick=\"startBatchAnalysis()\" style=\"margin-bottom:12px;background:#00d9ff\">📁 Batch Translate Folder</button>';");
                html.append("}");
                // File list
                html.append("html+='<ul class=\"file-list\">';");
                html.append("if(entries.length===0){html+='<li class=\"status-msg\">No files found</li>';}");
                html.append("entries.forEach(e=>{");
                // Determine icon and badge based on file type
                html.append("let icon='📁';let badge='';let onclick='';");
                html.append("if(e.isDirectory){icon='📁';onclick='loadDirectory(\\''+e.path+'\\')'}");
                html.append("else if(e.isVideo){icon='🎬';badge=e.hasSubtitle?'<span class=\"file-badge badge-sub\">✓ Sub</span>':'';onclick='selectVideo(\\''+e.path+'\\',\\''+e.name.replace(/'/g,\"\\\\\\'\")+'\\')';}");
                html.append("else if(e.isSubtitle){icon='📝';");
                // Show language badge - if already target language, no translate button needed
                html.append("let lang=e.language||'Unknown';");
                html.append("let isEnglish=lang.toLowerCase().includes('english');");
                html.append("badge='<span class=\"file-badge\" style=\"background:'+(isEnglish?'#00d9ff':'#ff9f43')+';color:#1a1a2e\">'+lang+'</span>';");
                html.append("onclick=isEnglish?'selectSubtitle(\\''+e.path+'\\',\\''+e.name.replace(/'/g,\"\\\\\\'\")+'\\')':\"\";");
                html.append("}");
                html.append("html+='<li class=\"file-item\"'+(onclick?' onclick=\"'+onclick+'\"':'')+' style=\"'+(onclick?'cursor:pointer':'cursor:default')+'\">';");
                html.append("html+='<span class=\"file-icon\">'+icon+'</span>';");
                html.append("html+='<span class=\"file-name\">'+e.name+badge+'</span>';");
                html.append("html+='</li>';});");
                html.append("html+='</ul>';");
                html.append("document.getElementById('browser').innerHTML=html;");
                html.append("}");

                // Select subtitle for translation
                html.append("function selectSubtitle(path,name){");
                html.append("if(!confirm('Translate \"'+name+'\" to ").append(currentLang).append("?'))return;");
                html.append("document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Translating... This may take a few minutes.</div>';");
                html.append("fetch('/api/browse/translate-local',{method:'POST',headers:{'Content-Type':'application/json'},");
                html.append("body:JSON.stringify({subtitlePath:path})}).then(r=>r.json()).then(r=>{");
                html.append("if(r.success){document.getElementById('browser').innerHTML='<div class=\"success-msg\">✓ Translated!<br><br><strong>'+r.path+'</strong><br><br>Language: '+r.language+'</div><button class=\"btn btn-secondary\" onclick=\"loadDirectory(\\''+currentPath+'\\')\" style=\"max-width:200px;margin:0 auto;display:block\">Browse More</button>';}");
                html.append("else{document.getElementById('browser').innerHTML='<div class=\"error-msg\">✗ '+r.error+'</div><button class=\"btn btn-secondary\" onclick=\"loadDirectory(\\''+currentPath+'\\')\" style=\"max-width:200px;margin:0 auto;display:block\">Try Again</button>';}");
                html.append("});");
                html.append("}");

                // Select video - check embedded subs first, then OpenSubtitles
                html.append("function selectVideo(path,name){");
                html.append("selectedVideo={path:path,name:name};");
                html.append("document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Checking embedded subtitles...</div>';");
                // First check for embedded subtitles
                html.append("fetch('/api/browse/embedded-tracks?videoPath='+encodeURIComponent(path)).then(r=>r.json()).then(embeddedResult=>{");
                html.append("if(embeddedResult.available && embeddedResult.tracks && embeddedResult.tracks.length>0){");
                html.append("renderEmbeddedOptions(embeddedResult.tracks);");
                html.append("}else{");
                // Fallback to OpenSubtitles
                html.append("document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Searching OpenSubtitles...</div>';");
                html.append("fetch('/api/browse/search?videoPath='+encodeURIComponent(path)).then(r=>r.json()).then(renderSubtitles).catch(e=>{");
                html.append("document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+e.message+'</div>';});");
                html.append("}");
                html.append("}).catch(e=>{");
                // If embedded check fails, fallback to OpenSubtitles
                html.append("fetch('/api/browse/search?videoPath='+encodeURIComponent(path)).then(r=>r.json()).then(renderSubtitles).catch(err=>{");
                html.append("document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+err.message+'</div>';});");
                html.append("});");
                html.append("}");

                // Render embedded subtitle options
                html.append("function renderEmbeddedOptions(tracks){");
                html.append("let html='<div class=\"breadcrumb\"><a href=\"#\" onclick=\"loadDirectory(\\''+currentPath+'\\');return false\">← Back</a></div>';");
                html.append("html+='<p style=\"margin:8px 0;word-break:break-word\">🎬 <strong>'+selectedVideo.name+'</strong></p>';");
                html.append("html+='<div class=\"card\" style=\"margin-bottom:12px\"><h2 style=\"color:#00ff88\">🎯 Embedded Subtitles Found!</h2>';");
                html.append("html+='<p style=\"font-size:0.85em;color:#888;margin-bottom:12px\">These are perfectly synced with your video.</p>';");
                html.append("html+='<ul class=\"subtitle-list\">';");
                html.append("tracks.forEach(t=>{");
                html.append("let isEng=(t.language||'').toLowerCase()==='eng'||(t.language||'').toLowerCase()==='en';");
                html.append("let langBadge=t.languageDisplay||t.language||'Unknown';");
                html.append("let badgeColor=isEng?'#00ff88':'#ff9f43';");
                html.append("html+='<li class=\"subtitle-item\">';");
                html.append("html+='<div class=\"subtitle-name\">'+t.displayName+' <span style=\"background:'+badgeColor+';color:#1a1a2e;font-size:0.75em;padding:2px 8px;border-radius:4px;margin-left:8px\">'+langBadge+'</span></div>';");
                html.append("html+='<div class=\"subtitle-meta\">Codec: '+(t.codec||'unknown')+'</div>';");
                html.append("html+='<button class=\"btn btn-primary subtitle-btn\" onclick=\"translateEmbedded('+t.index+')\">Extract & Translate</button>';");
                html.append("html+='</li>';});");
                html.append("html+='</ul></div>';");
                html.append("html+='<p style=\"text-align:center;color:#888;font-size:0.85em;margin:12px 0\">or</p>';");
                html.append("html+='<button class=\"btn btn-secondary\" onclick=\"searchOpenSubtitles()\">Search OpenSubtitles Instead</button>';");
                html.append("document.getElementById('browser').innerHTML=html;");
                html.append("}");

                // Search OpenSubtitles manually
                html.append("function searchOpenSubtitles(){");
                html.append("document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Searching OpenSubtitles...</div>';");
                html.append("fetch('/api/browse/search?videoPath='+encodeURIComponent(selectedVideo.path)).then(r=>r.json()).then(renderSubtitles).catch(e=>{");
                html.append("document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+e.message+'</div>';});");
                html.append("}");

                // Translate embedded subtitle
                html.append("function translateEmbedded(trackIndex){");
                html.append("showTranslatingUI();");
                html.append("fetch('/api/browse/translate-embedded',{method:'POST',headers:{'Content-Type':'application/json'},");
                html.append("body:JSON.stringify({videoPath:selectedVideo.path,trackIndex:trackIndex})}).then(r=>r.json()).then(handleTranslateResult);");
                html.append("}");

                // Render subtitles from OpenSubtitles
                html.append("function renderSubtitles(response){");
                html.append("let html='<div class=\"breadcrumb\"><a href=\"#\" onclick=\"loadDirectory(\\''+currentPath+'\\');return false\">← Back</a></div>';");
                html.append("html+='<p style=\"margin:8px 0;word-break:break-word\">🎬 <strong>'+selectedVideo.name+'</strong></p>';");
                // Manual search input
                html.append("html+='<div style=\"margin-bottom:12px\"><input type=\"text\" id=\"manualSearch\" placeholder=\"Manual search...\" style=\"width:70%;padding:10px;border-radius:6px 0 0 6px;border:1px solid rgba(255,255,255,0.2);background:rgba(0,0,0,0.3);color:#eee\">';");
                html.append("html+='<button onclick=\"doManualSearch()\" style=\"width:30%;padding:10px;border-radius:0 6px 6px 0;border:1px solid #00d9ff;background:#00d9ff;color:#1a1a2e;font-weight:bold;cursor:pointer\">Search</button></div>';");
                html.append("html+='<ul class=\"subtitle-list\">';");
                html.append("if(!response.data||response.data.length===0){html+='<li class=\"status-msg\">No subtitles found. Try manual search above.</li>';}");
                html.append("else{response.data.slice(0,20).forEach(s=>{");
                html.append("let attr=s.attributes||{};let file=attr.files&&attr.files[0]?attr.files[0]:{};");
                html.append("let name=attr.release||file.file_name||'Unknown';");
                html.append("let uploader=attr.uploader&&attr.uploader.name?attr.uploader.name:'Unknown';");
                html.append("let downloads=attr.download_count||0;");
                html.append("html+='<li class=\"subtitle-item\">';");
                html.append("html+='<div class=\"subtitle-name\">'+name+'</div>';");
                html.append("html+='<div class=\"subtitle-meta\">👤 '+uploader+' • ⬇️ '+downloads+'</div>';");
                html.append("html+='<button class=\"btn btn-primary subtitle-btn\" onclick=\"translateSubtitle('+file.file_id+',\\''+name.replace(/'/g,\"\\\\\\'\")+'\\')\">Translate & Save</button>';");
                html.append("html+='</li>';});}");
                html.append("html+='</ul>';");
                html.append("document.getElementById('browser').innerHTML=html;");
                html.append("}");

                // Show translating UI with progress polling
                html.append("function showTranslatingUI(){");
                html.append("document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Translating... This may take a few minutes.</div><div class=\"progress-bar\"><div class=\"progress-fill\" id=\"progress\" style=\"width:0%\"></div></div><div id=\"progress-text\" style=\"text-align:center;color:#888;font-size:0.85em\"></div>';");
                html.append("startProgressPolling();");
                html.append("}");

                // Poll for progress updates
                html.append("var progressInterval=null;");
                html.append("function startProgressPolling(){");
                html.append("if(progressInterval)clearInterval(progressInterval);");
                html.append("progressInterval=setInterval(()=>{");
                html.append("fetch('/api/browse/progress').then(r=>r.json()).then(r=>{");
                html.append("let t=r.translations;if(!t||Object.keys(t).length===0)return;");
                html.append("let first=Object.values(t)[0];");
                html.append("let pct=first.progressPercent||0;");
                html.append("let el=document.getElementById('progress');if(el)el.style.width=pct+'%';");
                html.append("let txt=document.getElementById('progress-text');if(txt)txt.textContent=first.completedEntries+'/'+first.totalEntries+' entries';");
                html.append("});},2000);");
                html.append("}");
                html.append("function stopProgressPolling(){if(progressInterval){clearInterval(progressInterval);progressInterval=null;}}");

                // Handle translate result
                html.append("function handleTranslateResult(r){");
                html.append("stopProgressPolling();");
                html.append("if(r.success){document.getElementById('browser').innerHTML='<div class=\"success-msg\">✓ Subtitle saved!<br><br><strong>'+r.path+'</strong><br><br>Language: '+r.language+'</div><button class=\"btn btn-secondary\" onclick=\"loadDirectory(\\''+currentPath+'\\')\" style=\"max-width:200px;margin:0 auto;display:block\">Browse More</button>';}");
                html.append("else{document.getElementById('browser').innerHTML='<div class=\"error-msg\">✗ '+r.error+'</div><button class=\"btn btn-secondary\" onclick=\"loadDirectory(\\''+currentPath+'\\')\" style=\"max-width:200px;margin:0 auto;display:block\">Try Again</button>';}");
                html.append("}");

                // Translate subtitle from OpenSubtitles
                html.append("function translateSubtitle(fileId,fileName){");
                html.append("showTranslatingUI();");
                html.append("fetch('/api/browse/translate',{method:'POST',headers:{'Content-Type':'application/json'},");
                html.append("body:JSON.stringify({fileId:fileId,videoPath:selectedVideo.path,fileName:fileName})}).then(r=>r.json()).then(handleTranslateResult);");
                html.append("}");

                // Manual search function
                html.append("function doManualSearch(){");
                html.append("var query=document.getElementById('manualSearch').value.trim();");
                html.append("if(!query){alert('Please enter a search term');return;}");
                html.append("document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Searching: '+query+'...</div>';");
                html.append("fetch('/api/browse/search-manual?query='+encodeURIComponent(query)).then(r=>r.json()).then(renderSubtitles).catch(e=>{");
                html.append("document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+e.message+'</div>';});");
                html.append("}");

                // ==================== Batch Translation Functions ====================

                // Start batch analysis
                html.append("function startBatchAnalysis(){");
                html.append("document.getElementById('browser').innerHTML='<div class=\"status-msg\"><span class=\"spinner\"></span> Analyzing folder for videos with English subtitles...</div>';");
                html.append("fetch('/api/browse/batch-analyze',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({folderPath:currentPath})})");
                html.append(".then(r=>r.json()).then(r=>{");
                html.append("if(r.error){document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+r.error+'</div><button class=\"btn btn-secondary\" onclick=\"loadDirectory(\\''+currentPath+'\\')\" style=\"max-width:200px;margin:12px auto;display:block\">Back</button>';return;}");
                html.append("renderBatchAnalysis(r);");
                html.append("}).catch(e=>{document.getElementById('browser').innerHTML='<div class=\"error-msg\">'+e.message+'</div>';});");
                html.append("}");

                // Render batch analysis results
                html.append("function renderBatchAnalysis(result){");
                html.append("let html='<div class=\"breadcrumb\"><a href=\"#\" onclick=\"loadDirectory(\\''+currentPath+'\\');return false\">← Back</a></div>';");
                html.append("html+='<div class=\"card\"><h2 style=\"color:#00d9ff\">📁 Batch Translation</h2>';");
                html.append("html+='<p style=\"color:#888;margin-bottom:12px\">Found <strong>'+result.totalVideos+'</strong> videos with English subtitles</p>';");
                html.append("if(result.totalVideos===0){html+='<p style=\"color:#ff6b6b\">No videos with English subtitles found in this folder.</p></div>';");
                html.append("document.getElementById('browser').innerHTML=html;return;}");
                html.append("html+='<ul class=\"subtitle-list\" style=\"max-height:300px;overflow-y:auto;margin-bottom:12px\">';");
                html.append("result.videos.forEach(v=>{html+='<li class=\"subtitle-item\" style=\"padding:8px\"><span>🎬 '+v.fileName+'</span></li>';});");
                html.append("html+='</ul>';");
                html.append("html+='<button class=\"btn btn-primary\" onclick=\"startBatchTranslation()\" style=\"margin-right:8px\">🚀 Start Translation</button>';");
                html.append("html+='<button class=\"btn btn-secondary\" onclick=\"loadDirectory(\\''+currentPath+'\\')\">Cancel</button>';");
                html.append("html+='</div>';");
                html.append("document.getElementById('browser').innerHTML=html;");
                html.append("}");

                // Start batch translation
                html.append("function startBatchTranslation(){");
                html.append("fetch('/api/browse/batch-start',{method:'POST'}).then(r=>r.json()).then(r=>{");
                html.append("if(r.error){alert('Error: '+r.error);return;}");
                html.append("showBatchProgress();");
                html.append("startBatchProgressPolling();");
                html.append("});");
                html.append("}");

                // Show batch progress UI
                html.append("function showBatchProgress(){");
                html.append("let html='<div class=\"card\"><h2 style=\"color:#00d9ff\">📁 Batch Translation in Progress</h2>';");
                html.append("html+='<div id=\"batch-status\"></div>';");
                html.append("html+='<div style=\"margin:16px 0\"><p style=\"color:#888;font-size:0.9em\">Overall Progress</p>';");
                html.append("html+='<div class=\"progress-bar\" style=\"height:24px\"><div id=\"batch-overall\" class=\"progress-fill\" style=\"width:0%\"></div></div>';");
                html.append("html+='<p id=\"batch-overall-text\" style=\"color:#888;font-size:0.85em;margin-top:4px\">0/0 videos</p></div>';");
                html.append("html+='<div style=\"margin:16px 0\"><p style=\"color:#888;font-size:0.9em\">Current File</p>';");
                html.append("html+='<p id=\"batch-current\" style=\"color:#00d9ff;font-weight:bold\">Starting...</p>';");
                html.append("html+='<div class=\"progress-bar\" style=\"height:20px\"><div id=\"batch-file\" class=\"progress-fill\" style=\"width:0%\"></div></div></div>';");
                html.append("html+='<button class=\"btn btn-secondary\" onclick=\"cancelBatch()\" style=\"background:#ff4757\">Cancel Batch</button>';");
                html.append("html+='</div>';");
                html.append("document.getElementById('browser').innerHTML=html;");
                html.append("}");

                // Batch progress polling
                html.append("var batchInterval=null;");
                html.append("function startBatchProgressPolling(){");
                html.append("batchInterval=setInterval(function(){");
                html.append("fetch('/api/browse/batch-progress').then(r=>r.json()).then(p=>{");
                html.append("if(!p.active){stopBatchProgressPolling();loadDirectory(currentPath);return;}");
                html.append("document.getElementById('batch-overall').style.width=p.progressPercent+'%';");
                html.append("document.getElementById('batch-overall-text').textContent=p.completedVideos+'/'+p.totalVideos+' videos';");
                html.append("document.getElementById('batch-current').textContent=p.currentVideo||'Processing...';");
                html.append("document.getElementById('batch-status').innerHTML='<p style=\"color:'+(p.status==='TRANSLATING'?'#00ff88':'#ff9f43')+'\">'+p.status+'</p>';");
                html.append("if(p.status==='COMPLETED'||p.status==='FAILED'||p.status==='CANCELLED'){stopBatchProgressPolling();");
                html.append("setTimeout(function(){loadDirectory(currentPath);},2000);}");
                html.append("});},3000);}");
                html.append("function stopBatchProgressPolling(){if(batchInterval){clearInterval(batchInterval);batchInterval=null;}}");

                // Cancel batch
                html.append("function cancelBatch(){");
                html.append("if(!confirm('Cancel batch translation?'))return;");
                html.append("fetch('/api/browse/batch-cancel',{method:'POST'}).then(()=>{stopBatchProgressPolling();loadDirectory(currentPath);});");
                html.append("}");

                html.append("</script>");
                html.append("</div></body></html>");

                return html.toString();
        }

        // Request/Response records
        public record TranslateRequest(int fileId, String videoPath, String fileName) {
        }

        public record TranslateLocalRequest(String subtitlePath) {
        }

        public record TranslateEmbeddedRequest(String videoPath, int trackIndex) {
        }

        public record SmbSettingsRequest(String host, String share, String username, String password, String domain) {
        }
}

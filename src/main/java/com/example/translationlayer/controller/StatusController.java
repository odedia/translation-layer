package com.example.translationlayer.controller;

import com.example.translationlayer.config.LanguageConfig;
import com.example.translationlayer.service.SubtitleService;
import com.example.translationlayer.service.TranslationProgressTracker;
import com.example.translationlayer.service.TranslationProgressTracker.TranslationProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Web UI controller for monitoring translation status.
 */
@Controller
public class StatusController {

    private static final Logger log = LoggerFactory.getLogger(StatusController.class);

    private final SubtitleService subtitleService;
    private final LanguageConfig languageConfig;
    private final TranslationProgressTracker progressTracker;

    @org.springframework.beans.factory.annotation.Value("${translation.cache.directory:${HOME}/.subtitle-cache}")
    private String cacheDirectory;

    public StatusController(SubtitleService subtitleService, LanguageConfig languageConfig,
            TranslationProgressTracker progressTracker) {
        this.subtitleService = subtitleService;
        this.languageConfig = languageConfig;
        this.progressTracker = progressTracker;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/status";
    }

    @PostMapping("/language")
    @ResponseBody
    public String changeLanguage(@RequestParam String language) {
        languageConfig.setTargetLanguage(language);
        return "OK";
    }

    @GetMapping("/status")
    @ResponseBody
    public String statusPage() throws IOException {
        String currentLang = languageConfig.getTargetLanguage();
        StringBuilder html = new StringBuilder();

        // HTML head and styles
        html.append("<!DOCTYPE html><html><head>");
        html.append("<title>Translation Layer - Status</title>");
        html.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        html.append("<meta http-equiv=\"refresh\" content=\"5\">");
        html.append("<style>");
        html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;");
        html.append(
                "background:linear-gradient(135deg,#1a1a2e 0%,#16213e 100%);color:#eee;margin:0;padding:20px;min-height:100vh}");
        html.append(".container{max-width:1000px;margin:0 auto}");
        html.append("h1{color:#00d9ff;text-align:center;margin-bottom:10px}");
        html.append(
                ".subtitle{text-align:center;color:#888;margin-bottom:30px;display:flex;flex-wrap:wrap;justify-content:center;align-items:center;gap:10px}");
        html.append(
                ".lang-select{background:rgba(0,217,255,0.2);border:1px solid #00d9ff;color:#00d9ff;padding:8px 12px;border-radius:6px;font-size:16px;cursor:pointer;font-weight:bold}");
        html.append(
                ".status-card{background:rgba(255,255,255,0.1);border-radius:12px;padding:20px;margin-bottom:20px;backdrop-filter:blur(10px);overflow-x:auto}");
        html.append(
                ".status-card h2{margin-top:0;color:#00d9ff;display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:10px}");
        html.append("table{width:100%;border-collapse:collapse;min-width:600px}");
        html.append("th,td{padding:12px 8px;text-align:left;border-bottom:1px solid rgba(255,255,255,0.1)}");
        html.append("td{word-break:break-word;max-width:200px}");
        html.append("th{color:#00d9ff;white-space:nowrap}");
        html.append(".status-ready{color:#00ff88;font-weight:bold}");
        html.append(
                ".btn{padding:8px 16px;border-radius:6px;text-decoration:none;font-weight:bold;border:none;cursor:pointer;font-size:14px}");
        html.append(".download-btn{background:#00d9ff;color:#1a1a2e}");
        html.append(".delete-btn{background:#ff4757;color:white;margin-left:8px}");
        html.append(".clear-all-btn{background:#ff4757;color:white;font-size:12px;padding:6px 12px}");
        html.append(".empty{text-align:center;color:#888;padding:40px}");
        html.append(".progress-bar{background:#333;border-radius:4px;height:20px;width:200px;display:inline-block}");
        html.append(".progress-fill{background:#00d9ff;height:20px;border-radius:4px}");
        html.append(
                ".rtl-badge{background:#ff9f43;color:#1a1a2e;font-size:10px;padding:2px 6px;border-radius:4px;margin-left:6px}");
        html.append("</style>");
        html.append("<script>");
        html.append(
                "function changeLanguage(lang){fetch('/language',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'language='+encodeURIComponent(lang)}).then(()=>location.reload())}");
        html.append(
                "function deleteCache(id){if(confirm('Delete '+id+'?')){fetch('/cache/'+id,{method:'DELETE'}).then(()=>location.reload())}}");
        html.append(
                "function clearAllCache(){if(confirm('Delete ALL cached subtitles?')){fetch('/cache',{method:'DELETE'}).then(()=>location.reload())}}");
        html.append("</script></head><body><div class=\"container\">");
        html.append("<h1>🎬 Translation Layer</h1>");

        // Language selector
        html.append(
                "<p class=\"subtitle\">English → <select class=\"lang-select\" onchange=\"changeLanguage(this.value)\">");
        for (String lang : languageConfig.getSupportedLanguages().keySet()) {
            String selected = lang.equals(currentLang) ? " selected" : "";
            String rtl = languageConfig.isRtlLanguage(lang) ? " (RTL)" : "";
            html.append("<option value=\"").append(lang).append("\"").append(selected).append(">").append(lang)
                    .append(rtl).append("</option>");
        }
        html.append("</select> Subtitle Translation");
        if (languageConfig.isCurrentLanguageRtl()) {
            html.append("<span class='rtl-badge'>RTL</span>");
        }
        html.append("</p>");

        // Active translations section
        Map<String, TranslationProgress> active = progressTracker.getActiveTranslations();
        if (!active.isEmpty()) {
            html.append("<div class=\"status-card\"><h2>⏳ Translations In Progress</h2>");
            html.append("<table><tr><th>File ID</th><th>File Name</th><th>Progress</th><th>Elapsed</th></tr>");
            for (TranslationProgress p : active.values()) {
                long sec = Duration.between(p.startTime(), Instant.now()).toSeconds();
                String elapsed = sec < 60 ? sec + "s" : (sec / 60) + "m " + (sec % 60) + "s";
                int pct = p.progressPercent();
                html.append("<tr><td>").append(p.fileId()).append("</td>");
                html.append("<td>").append(p.fileName()).append("</td>");
                html.append("<td><div class='progress-bar'><div class='progress-fill' style='width:").append(pct)
                        .append("%'></div></div> ").append(pct).append("%</td>");
                html.append("<td>").append(elapsed).append("</td></tr>");
            }
            html.append("</table></div>");
        }

        // Cached translations section
        html.append("<div class=\"status-card\"><h2><span>📁 Cached Translations</span>");

        Path cachePath = Path.of(cacheDirectory);
        List<CachedSubtitle> cached = new ArrayList<>();

        if (Files.exists(cachePath)) {
            try (Stream<Path> dirs = Files.list(cachePath)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    try {
                        String fileId = dir.getFileName().toString();
                        Path translatedPath = dir.resolve("translated_he.srt");
                        Path originalPath = dir.resolve("original.srt");
                        Path metadataPath = dir.resolve("metadata.json");

                        boolean hasTranslated = Files.exists(translatedPath);
                        boolean hasOriginal = Files.exists(originalPath);

                        String fileName = null;
                        if (Files.exists(metadataPath)) {
                            try {
                                String metadata = Files.readString(metadataPath);
                                int start = metadata.indexOf("\"fileName\":\"");
                                if (start >= 0) {
                                    start += 12;
                                    int end = metadata.indexOf("\"", start);
                                    if (end > start) {
                                        fileName = metadata.substring(start, end);
                                    }
                                }
                            } catch (IOException e) {
                                // Ignore
                            }
                        }

                        if (hasOriginal || hasTranslated) {
                            long size = hasTranslated ? Files.size(translatedPath) : 0;
                            Instant modified = hasTranslated ? Files.getLastModifiedTime(translatedPath).toInstant()
                                    : Files.getLastModifiedTime(originalPath).toInstant();
                            cached.add(new CachedSubtitle(fileId, fileName, hasTranslated, size, modified));
                        }
                    } catch (IOException e) {
                        // Skip
                    }
                });
            }
        }

        if (!cached.isEmpty()) {
            html.append("<button class='btn clear-all-btn' onclick='clearAllCache()'>🗑 Clear All</button>");
        }
        html.append("</h2>");

        if (cached.isEmpty()) {
            html.append("<p class='empty'>No translations cached yet.</p>");
        } else {
            html.append(
                    "<table><tr><th>File ID</th><th>File Name</th><th>Status</th><th>Size</th><th>Updated</th><th>Actions</th></tr>");

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
            cached.sort((a, b) -> b.modified.compareTo(a.modified));

            for (CachedSubtitle sub : cached) {
                String status = sub.translated ? "<span class='status-ready'>✓ Ready</span>"
                        : "<span style='color:#ffaa00'>⏳ In Progress</span>";
                String size = sub.translated ? formatSize(sub.size) : "-";
                String time = fmt.format(sub.modified);
                String name = sub.fileName != null ? sub.fileName : "-";
                String download = sub.translated
                        ? "<a href='/api/v1/download/" + sub.fileId
                                + "/subtitle.srt' class='btn download-btn'>Download</a>"
                        : "";
                String del = "<button class='btn delete-btn' onclick='deleteCache(\"" + sub.fileId + "\")'>🗑</button>";

                html.append("<tr><td>").append(sub.fileId).append("</td>");
                html.append("<td>").append(name).append("</td>");
                html.append("<td>").append(status).append("</td>");
                html.append("<td>").append(size).append("</td>");
                html.append("<td>").append(time).append("</td>");
                html.append("<td>").append(download).append(del).append("</td></tr>");
            }
            html.append("</table>");
        }

        html.append("</div><p style='text-align:center;color:#666;font-size:12px'>Auto-refreshes every 5 seconds</p>");
        html.append("</div></body></html>");

        return html.toString();
    }

    @DeleteMapping("/cache/{fileId}")
    @ResponseBody
    public String deleteCache(@PathVariable String fileId) throws IOException {
        Path cacheDir = Path.of(cacheDirectory, fileId);
        if (Files.exists(cacheDir)) {
            deleteDirectoryRecursively(cacheDir);
            log.info("Deleted cache for file_id: {}", fileId);
            return "Deleted";
        }
        return "Not found";
    }

    @DeleteMapping("/cache")
    @ResponseBody
    public String clearAllCache() throws IOException {
        Path cachePath = Path.of(cacheDirectory);
        if (Files.exists(cachePath)) {
            try (Stream<Path> dirs = Files.list(cachePath)) {
                dirs.filter(Files::isDirectory).forEach(dir -> {
                    try {
                        deleteDirectoryRecursively(dir);
                    } catch (IOException e) {
                        log.error("Failed to delete: {}", dir, e);
                    }
                });
            }
            log.info("Cleared all cached subtitles");
        }
        return "Cleared";
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String formatSize(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private record CachedSubtitle(String fileId, String fileName, boolean translated, long size, Instant modified) {
    }
}

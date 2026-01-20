package com.example.translationlayer.service;

import com.example.translationlayer.config.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Service for browsing and writing files on the local file system.
 */
@Service
public class LocalFileService implements FileSystemService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileService.class);

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "ts");

    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(
            "srt", "sub", "ass", "ssa", "vtt");

    // Pattern to extract language code from subtitle filename
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile(
            "\\.([a-z]{2,3})\\.[a-z]{3}$", Pattern.CASE_INSENSITIVE);

    // Common language codes
    private static final Map<String, String> LANGUAGE_CODES = Map.ofEntries(
            Map.entry("en", "English"), Map.entry("eng", "English"),
            Map.entry("he", "Hebrew"), Map.entry("heb", "Hebrew"),
            Map.entry("ar", "Arabic"), Map.entry("ara", "Arabic"),
            Map.entry("es", "Spanish"), Map.entry("spa", "Spanish"),
            Map.entry("fr", "French"), Map.entry("fra", "French"),
            Map.entry("de", "German"), Map.entry("deu", "German"), Map.entry("ger", "German"),
            Map.entry("it", "Italian"), Map.entry("ita", "Italian"),
            Map.entry("pt", "Portuguese"), Map.entry("por", "Portuguese"),
            Map.entry("ru", "Russian"), Map.entry("rus", "Russian"),
            Map.entry("zh", "Chinese"), Map.entry("chi", "Chinese"), Map.entry("zho", "Chinese"),
            Map.entry("ja", "Japanese"), Map.entry("jpn", "Japanese"),
            Map.entry("ko", "Korean"), Map.entry("kor", "Korean"));

    private final AppSettings appSettings;

    public LocalFileService(AppSettings appSettings) {
        this.appSettings = appSettings;
    }

    @Override
    public boolean isConfigured() {
        String rootPath = appSettings.getLocalRootPath();
        if (rootPath == null || rootPath.isBlank()) {
            return false;
        }
        Path root = Path.of(rootPath);
        return Files.exists(root) && Files.isDirectory(root);
    }

    @Override
    public String testConnection() {
        String rootPath = appSettings.getLocalRootPath();
        if (rootPath == null || rootPath.isBlank()) {
            return "Local root path not configured";
        }
        Path root = Path.of(rootPath);
        if (!Files.exists(root)) {
            return "Path does not exist: " + rootPath;
        }
        if (!Files.isDirectory(root)) {
            return "Path is not a directory: " + rootPath;
        }
        if (!Files.isReadable(root)) {
            return "Path is not readable: " + rootPath;
        }
        log.info("Local file system test successful: {}", rootPath);
        return null; // Success
    }

    @Override
    public List<SmbService.FileEntry> listDirectory(String path) throws IOException {
        Path root = Path.of(appSettings.getLocalRootPath());
        Path targetDir = path.isBlank() ? root : root.resolve(path);

        // Security: prevent path traversal
        if (!targetDir.normalize().startsWith(root.normalize())) {
            throw new IOException("Access denied: path outside root directory");
        }

        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            throw new IOException("Directory not found: " + path);
        }

        List<SmbService.FileEntry> entries = new ArrayList<>();
        Set<String> videoFiles = new HashSet<>();
        Map<String, SmbService.FileEntry> subtitleMap = new HashMap<>();

        // First pass: identify all videos and subtitles
        try (Stream<Path> stream = Files.list(targetDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                String lowerName = name.toLowerCase();
                String relativePath = root.relativize(p).toString().replace("\\", "/");

                if (Files.isDirectory(p)) {
                    entries.add(new SmbService.FileEntry(name, relativePath, true, false, false, false, null));
                } else {
                    String ext = getExtension(lowerName);
                    if (VIDEO_EXTENSIONS.contains(ext)) {
                        videoFiles.add(getBaseName(name));
                    } else if (SUBTITLE_EXTENSIONS.contains(ext)) {
                        String lang = detectLanguage(name);
                        subtitleMap.put(name,
                                new SmbService.FileEntry(name, relativePath, false, false, false, true, lang));
                    }
                }
            });
        }

        // Second pass: add videos with subtitle info
        try (Stream<Path> stream = Files.list(targetDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                String ext = getExtension(name.toLowerCase());

                if (VIDEO_EXTENSIONS.contains(ext) && !Files.isDirectory(p)) {
                    String baseName = getBaseName(name);
                    String relativePath = root.relativize(p).toString().replace("\\", "/");
                    boolean hasSubtitle = subtitleMap.keySet().stream()
                            .anyMatch(sub -> sub.toLowerCase().startsWith(baseName.toLowerCase()));
                    entries.add(new SmbService.FileEntry(name, relativePath, false, true, hasSubtitle, false, null));
                }
            });
        }

        // Add subtitles
        entries.addAll(subtitleMap.values());

        // Sort: directories first, then by name
        entries.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });

        return entries;
    }

    @Override
    public String writeSubtitle(String videoPath, String content, String languageCode) throws IOException {
        Path root = Path.of(appSettings.getLocalRootPath());
        Path videoFile = root.resolve(videoPath);

        // Security check
        if (!videoFile.normalize().startsWith(root.normalize())) {
            throw new IOException("Access denied: path outside root directory");
        }

        String videoName = videoFile.getFileName().toString();
        String baseName = getBaseName(videoName);
        String subtitleName = baseName + "." + languageCode + ".srt";
        Path subtitlePath = videoFile.getParent().resolve(subtitleName);

        Files.writeString(subtitlePath, content);
        log.info("Saved subtitle to: {}", subtitlePath);

        return root.relativize(subtitlePath).toString().replace("\\", "/");
    }

    @Override
    public void writeSubtitleDirect(String subtitlePath, String content) throws IOException {
        Path root = Path.of(appSettings.getLocalRootPath());
        Path fullPath = root.resolve(subtitlePath);

        // Security check
        if (!fullPath.normalize().startsWith(root.normalize())) {
            throw new IOException("Access denied: path outside root directory");
        }

        Files.writeString(fullPath, content);
        log.info("Saved subtitle directly to: {}", fullPath);
    }

    @Override
    public String readSubtitleContent(String subtitlePath) throws IOException {
        Path root = Path.of(appSettings.getLocalRootPath());
        Path fullPath = root.resolve(subtitlePath);

        // Security check
        if (!fullPath.normalize().startsWith(root.normalize())) {
            throw new IOException("Access denied: path outside root directory");
        }

        return Files.readString(fullPath);
    }

    @Override
    public Path downloadToTempFile(String remotePath) throws IOException {
        // For local files, we don't need to download - just return the actual path
        // But FFmpeg expects a temp file that can be deleted, so we copy it
        Path root = Path.of(appSettings.getLocalRootPath());
        Path sourcePath = root.resolve(remotePath);

        // Security check
        if (!sourcePath.normalize().startsWith(root.normalize())) {
            throw new IOException("Access denied: path outside root directory");
        }

        String fileName = sourcePath.getFileName().toString();
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        Path tempFile = Files.createTempFile("video_", extension);

        Files.copy(sourcePath, tempFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied video to temp file: {} -> {}", sourcePath.getFileName(), tempFile);

        return tempFile;
    }

    @Override
    public Path downloadHeaderToTempFile(String remotePath) throws IOException {
        // Copy first 20MB for metadata analysis
        Path root = Path.of(appSettings.getLocalRootPath());
        Path sourcePath = root.resolve(remotePath);

        // Security check
        if (!sourcePath.normalize().startsWith(root.normalize())) {
            throw new IOException("Access denied: path outside root directory");
        }

        String fileName = sourcePath.getFileName().toString();
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        Path tempFile = Files.createTempFile("video_header_", extension);

        long maxBytes = 20 * 1024 * 1024; // 20MB
        long fileSize = Files.size(sourcePath);
        long bytesToCopy = Math.min(fileSize, maxBytes);

        try (var in = Files.newInputStream(sourcePath);
                var out = Files.newOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            long copied = 0;
            int read;
            while (copied < bytesToCopy && (read = in.read(buffer)) != -1) {
                int toWrite = (int) Math.min(read, bytesToCopy - copied);
                out.write(buffer, 0, toWrite);
                copied += toWrite;
            }
        }

        log.info("Copied {} bytes of header to temp file", bytesToCopy);
        return tempFile;
    }

    @Override
    public String extractVideoTitle(String videoPath) {
        String fileName = videoPath.contains("/")
                ? videoPath.substring(videoPath.lastIndexOf('/') + 1)
                : videoPath;

        // Remove extension
        String baseName = getBaseName(fileName);

        // Clean up common patterns
        return baseName
                .replaceAll("\\[.*?\\]", "") // Remove [tags]
                .replaceAll("\\(.*?\\)", "") // Remove (tags)
                .replaceAll("\\d{3,4}p", "") // Remove resolution
                .replaceAll("(?i)(x264|x265|hevc|aac|bluray|webrip|hdtv|xvid)", "")
                .replaceAll("[._]", " ") // Replace dots/underscores with spaces
                .replaceAll("\\s+", " ") // Collapse multiple spaces
                .trim();
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1).toLowerCase() : "";
    }

    private String getBaseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String detectLanguage(String filename) {
        Matcher m = LANGUAGE_PATTERN.matcher(filename);
        if (m.find()) {
            String code = m.group(1).toLowerCase();
            return LANGUAGE_CODES.getOrDefault(code, code.toUpperCase());
        }
        return "Unknown";
    }
}

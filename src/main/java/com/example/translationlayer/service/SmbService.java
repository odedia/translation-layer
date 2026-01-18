package com.example.translationlayer.service;

import com.example.translationlayer.config.SmbConfig;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Service for browsing and writing files to SMB/NAS shares.
 */
@Service
public class SmbService {

    private static final Logger log = LoggerFactory.getLogger(SmbService.class);

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mkv", ".mp4", ".avi", ".mov", ".m4v", ".wmv", ".ts", ".webm", ".flv", ".mpeg", ".mpg");

    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of(
            ".srt", ".sub", ".ass", ".ssa", ".vtt");

    // Common language codes in subtitle filenames
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
            Map.entry("ko", "Korean"), Map.entry("kor", "Korean"),
            Map.entry("nl", "Dutch"), Map.entry("nld", "Dutch"),
            Map.entry("pl", "Polish"), Map.entry("pol", "Polish"),
            Map.entry("tr", "Turkish"), Map.entry("tur", "Turkish"),
            Map.entry("sv", "Swedish"), Map.entry("swe", "Swedish"),
            Map.entry("da", "Danish"), Map.entry("dan", "Danish"),
            Map.entry("no", "Norwegian"), Map.entry("nor", "Norwegian"),
            Map.entry("fi", "Finnish"), Map.entry("fin", "Finnish"),
            Map.entry("el", "Greek"), Map.entry("ell", "Greek"),
            Map.entry("hu", "Hungarian"), Map.entry("hun", "Hungarian"),
            Map.entry("cs", "Czech"), Map.entry("ces", "Czech"),
            Map.entry("ro", "Romanian"), Map.entry("ron", "Romanian"),
            Map.entry("th", "Thai"), Map.entry("tha", "Thai"),
            Map.entry("vi", "Vietnamese"), Map.entry("vie", "Vietnamese"),
            Map.entry("id", "Indonesian"), Map.entry("ind", "Indonesian"),
            Map.entry("hi", "Hindi"), Map.entry("hin", "Hindi"),
            Map.entry("fa", "Persian"), Map.entry("fas", "Persian"),
            Map.entry("uk", "Ukrainian"), Map.entry("ukr", "Ukrainian"),
            Map.entry("bg", "Bulgarian"), Map.entry("bul", "Bulgarian"));

    private final SmbConfig smbConfig;

    public SmbService(SmbConfig smbConfig) {
        this.smbConfig = smbConfig;
    }

    /**
     * Test the SMB connection with current settings.
     * 
     * @return null if successful, error message if failed
     */
    public String testConnection() {
        if (!smbConfig.isConfigured()) {
            return "SMB not configured. Please set host and share name.";
        }

        try (SMBClient client = new SMBClient();
                Connection connection = client.connect(smbConfig.getHost());
                Session session = connection.authenticate(createAuthContext());
                DiskShare share = (DiskShare) session.connectShare(smbConfig.getShare())) {

            // Try to list root to verify access
            share.list("");
            log.info("SMB connection test successful");
            return null; // Success

        } catch (Exception e) {
            log.error("SMB connection test failed", e);
            return "Connection failed: " + e.getMessage();
        }
    }

    /**
     * List directory contents at the given path.
     * 
     * @param path Path relative to share root (empty string for root)
     * @return List of file/folder entries
     */
    public List<FileEntry> listDirectory(String path) throws IOException {
        if (!smbConfig.isConfigured()) {
            throw new IOException("SMB not configured");
        }

        String normalizedPath = normalizePath(path);
        List<FileEntry> entries = new ArrayList<>();

        try (SMBClient client = new SMBClient();
                Connection connection = client.connect(smbConfig.getHost());
                Session session = connection.authenticate(createAuthContext());
                DiskShare share = (DiskShare) session.connectShare(smbConfig.getShare())) {

            for (FileIdBothDirectoryInformation info : share.list(normalizedPath)) {
                String name = info.getFileName();

                // Skip . and .. entries
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }

                boolean isDirectory = (info.getFileAttributes()
                        & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                String fullPath = normalizedPath.isEmpty() ? name : normalizedPath + "/" + name;

                if (isDirectory) {
                    entries.add(new FileEntry(name, fullPath, true, false, false, false, null));
                } else {
                    String lowerName = name.toLowerCase();
                    boolean isVideo = VIDEO_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
                    boolean isSubtitle = SUBTITLE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);

                    if (isVideo) {
                        // Check if subtitle already exists for this video
                        boolean hasSubtitle = checkSubtitleExists(share, fullPath);
                        entries.add(new FileEntry(name, fullPath, false, true, hasSubtitle, false, null));
                    } else if (isSubtitle) {
                        // Detect language from filename
                        String language = detectLanguageFromFilename(name);
                        entries.add(new FileEntry(name, fullPath, false, false, false, true, language));
                    }
                    // Skip other files
                }
            }

            // Sort: directories first, then alphabetically
            entries.sort((a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.name().compareToIgnoreCase(b.name());
            });

            return entries;

        } catch (Exception e) {
            log.error("Failed to list directory: {}", normalizedPath, e);
            throw new IOException("Failed to list directory: " + e.getMessage(), e);
        }
    }

    /**
     * Write a subtitle file next to the video file.
     * 
     * @param videoPath    Path to the video file (relative to share)
     * @param srtContent   The subtitle content
     * @param languageCode Language code (e.g., "he" for Hebrew)
     * @return The path of the created subtitle file
     */
    public String writeSubtitle(String videoPath, String srtContent, String languageCode) throws IOException {
        if (!smbConfig.isConfigured()) {
            throw new IOException("SMB not configured");
        }

        // Generate subtitle filename: video.he.srt
        String subtitlePath = generateSubtitlePath(videoPath, languageCode);
        String normalizedPath = normalizePath(subtitlePath);

        try (SMBClient client = new SMBClient();
                Connection connection = client.connect(smbConfig.getHost());
                Session session = connection.authenticate(createAuthContext());
                DiskShare share = (DiskShare) session.connectShare(smbConfig.getShare())) {

            // Create/overwrite the subtitle file
            try (File file = share.openFile(
                    normalizedPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {

                try (OutputStream os = file.getOutputStream()) {
                    // Write with BOM for better compatibility
                    os.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
                    os.write(srtContent.getBytes(StandardCharsets.UTF_8));
                }
            }

            log.info("Wrote subtitle to: {}", subtitlePath);
            return subtitlePath;

        } catch (Exception e) {
            log.error("Failed to write subtitle: {}", subtitlePath, e);
            throw new IOException("Failed to write subtitle: " + e.getMessage(), e);
        }
    }

    /**
     * Write subtitle content directly to a specified path (no path generation).
     * 
     * @param subtitlePath Full path for the subtitle file (relative to share)
     * @param srtContent   The subtitle content
     * @return The path of the created subtitle file
     */
    public String writeSubtitleDirect(String subtitlePath, String srtContent) throws IOException {
        if (!smbConfig.isConfigured()) {
            throw new IOException("SMB not configured");
        }

        String normalizedPath = normalizePath(subtitlePath);

        try (SMBClient client = new SMBClient();
                Connection connection = client.connect(smbConfig.getHost());
                Session session = connection.authenticate(createAuthContext());
                DiskShare share = (DiskShare) session.connectShare(smbConfig.getShare())) {

            try (File file = share.openFile(
                    normalizedPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {

                try (OutputStream os = file.getOutputStream()) {
                    // Write with BOM for better compatibility
                    os.write(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF });
                    os.write(srtContent.getBytes(StandardCharsets.UTF_8));
                }
            }

            log.info("Wrote subtitle directly to: {}", subtitlePath);
            return subtitlePath;

        } catch (Exception e) {
            log.error("Failed to write subtitle: {}", subtitlePath, e);
            throw new IOException("Failed to write subtitle: " + e.getMessage(), e);
        }
    }

    /**
     * Extract the video title from the filename for OpenSubtitles search.
     */
    public String extractVideoTitle(String videoPath) {
        String filename = videoPath.contains("/")
                ? videoPath.substring(videoPath.lastIndexOf('/') + 1)
                : videoPath;

        // Remove extension
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0) {
            filename = filename.substring(0, dotIndex);
        }

        // Clean up common patterns
        return filename
                .replaceAll("\\.", " ")
                .replaceAll("_", " ")
                .replaceAll("\\[.*?\\]", "")
                .replaceAll("\\(.*?\\)", "")
                .replaceAll("(?i)(720p|1080p|2160p|4k|bluray|brrip|webrip|hdtv|x264|x265|hevc|aac|dts)", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean checkSubtitleExists(DiskShare share, String videoPath) {
        // Check for any subtitle file with same base name
        String basePath = videoPath.substring(0, videoPath.lastIndexOf('.'));

        for (String ext : SUBTITLE_EXTENSIONS) {
            try {
                if (share.fileExists(basePath + ext))
                    return true;
                // Also check language-specific subtitles
                if (share.fileExists(basePath + ".he" + ext))
                    return true;
                if (share.fileExists(basePath + ".en" + ext))
                    return true;
            } catch (Exception e) {
                // Ignore - file doesn't exist
            }
        }
        return false;
    }

    private String generateSubtitlePath(String videoPath, String languageCode) {
        int dotIndex = videoPath.lastIndexOf('.');
        if (dotIndex > 0) {
            return videoPath.substring(0, dotIndex) + "." + languageCode + ".srt";
        }
        return videoPath + "." + languageCode + ".srt";
    }

    private String normalizePath(String path) {
        if (path == null)
            return "";
        // SMB uses backslashes, but we'll handle both
        return path.replace("\\", "/").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private AuthenticationContext createAuthContext() {
        String username = smbConfig.getUsername();
        String password = smbConfig.getPassword();
        String domain = smbConfig.getDomain();

        if (username.isBlank()) {
            return AuthenticationContext.anonymous();
        }

        return new AuthenticationContext(
                username,
                password.toCharArray(),
                domain.isBlank() ? null : domain);
    }

    /**
     * Download a file from SMB to a temporary local file.
     * Used for FFmpeg to analyze embedded subtitles.
     * 
     * @param remotePath Path to file on SMB share
     * @return Path to local temp file (caller must delete when done)
     */
    public java.nio.file.Path downloadToTempFile(String remotePath) throws IOException {
        if (!smbConfig.isConfigured()) {
            throw new IOException("SMB not configured");
        }

        String normalizedPath = normalizePath(remotePath);
        String fileName = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath;

        // Create temp file with same extension
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("video_", extension);

        try (SMBClient client = new SMBClient();
                Connection connection = client.connect(smbConfig.getHost());
                Session session = connection.authenticate(createAuthContext());
                DiskShare share = (DiskShare) session.connectShare(smbConfig.getShare())) {

            try (File file = share.openFile(
                    normalizedPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                    InputStream is = file.getInputStream();
                    OutputStream os = java.nio.file.Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                log.info("Downloaded {} bytes to temp file for FFmpeg analysis", totalBytes);
            }

            return tempFile;

        } catch (Exception e) {
            // Clean up temp file on error
            java.nio.file.Files.deleteIfExists(tempFile);
            log.error("Failed to download file: {}", remotePath, e);
            throw new IOException("Failed to download file: " + e.getMessage(), e);
        }
    }

    /**
     * Download only the first N bytes of a video file for metadata analysis.
     * This is much faster than downloading the entire file.
     * 
     * @param remotePath Path to file on SMB share
     * @param maxBytes   Maximum bytes to download (default 20MB is usually enough
     *                   for metadata)
     * @return Path to local temp file (caller must delete when done)
     */
    public java.nio.file.Path downloadHeaderToTempFile(String remotePath, long maxBytes) throws IOException {
        if (!smbConfig.isConfigured()) {
            throw new IOException("SMB not configured");
        }

        String normalizedPath = normalizePath(remotePath);
        String fileName = remotePath.contains("/")
                ? remotePath.substring(remotePath.lastIndexOf('/') + 1)
                : remotePath;

        // Create temp file with same extension
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("video_header_", extension);

        try (SMBClient client = new SMBClient();
                Connection connection = client.connect(smbConfig.getHost());
                Session session = connection.authenticate(createAuthContext());
                DiskShare share = (DiskShare) session.connectShare(smbConfig.getShare())) {

            try (File file = share.openFile(
                    normalizedPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
                    InputStream is = file.getInputStream();
                    OutputStream os = java.nio.file.Files.newOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                while ((bytesRead = is.read(buffer)) != -1 && totalBytes < maxBytes) {
                    // Don't write more than maxBytes
                    int bytesToWrite = (int) Math.min(bytesRead, maxBytes - totalBytes);
                    os.write(buffer, 0, bytesToWrite);
                    totalBytes += bytesToWrite;
                    if (totalBytes >= maxBytes) {
                        break;
                    }
                }
                log.info("Downloaded {} bytes (header only) for FFmpeg analysis", totalBytes);
            }

            return tempFile;

        } catch (Exception e) {
            // Clean up temp file on error
            java.nio.file.Files.deleteIfExists(tempFile);
            log.error("Failed to download file header: {}", remotePath, e);
            throw new IOException("Failed to download file header: " + e.getMessage(), e);
        }
    }

    /**
     * Download first 20MB of video for metadata analysis.
     */
    public java.nio.file.Path downloadHeaderToTempFile(String remotePath) throws IOException {
        // 20MB is typically enough for ffprobe to read all metadata
        return downloadHeaderToTempFile(remotePath, 20 * 1024 * 1024);
    }

    /**
     * Detect language from subtitle filename.
     * Examples: movie.en.srt -> English, movie.hebrew.srt -> Hebrew
     */
    private String detectLanguageFromFilename(String filename) {
        // Remove extension
        String base = filename.toLowerCase();
        for (String ext : SUBTITLE_EXTENSIONS) {
            if (base.endsWith(ext)) {
                base = base.substring(0, base.length() - ext.length());
                break;
            }
        }

        // Check for language code at end (e.g., movie.en or movie.eng)
        int lastDot = base.lastIndexOf('.');
        if (lastDot > 0) {
            String possibleLang = base.substring(lastDot + 1);
            if (LANGUAGE_CODES.containsKey(possibleLang)) {
                return LANGUAGE_CODES.get(possibleLang);
            }
            // Check common full language names
            if (possibleLang.equals("english"))
                return "English";
            if (possibleLang.equals("hebrew"))
                return "Hebrew";
            if (possibleLang.equals("spanish"))
                return "Spanish";
            if (possibleLang.equals("french"))
                return "French";
            if (possibleLang.equals("german"))
                return "German";
            if (possibleLang.equals("arabic"))
                return "Arabic";
            if (possibleLang.equals("russian"))
                return "Russian";
            if (possibleLang.equals("chinese"))
                return "Chinese";
            if (possibleLang.equals("japanese"))
                return "Japanese";
        }

        // Default to unknown (assume English for now)
        return "English (assumed)";
    }

    /**
     * Read subtitle content from SMB share.
     */
    public String readSubtitleContent(String subtitlePath) throws IOException {
        if (!smbConfig.isConfigured()) {
            throw new IOException("SMB not configured");
        }

        String normalizedPath = normalizePath(subtitlePath);

        try (SMBClient client = new SMBClient();
                Connection connection = client.connect(smbConfig.getHost());
                Session session = connection.authenticate(createAuthContext());
                DiskShare share = (DiskShare) session.connectShare(smbConfig.getShare())) {

            try (File file = share.openFile(
                    normalizedPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {

                try (InputStream is = file.getInputStream()) {
                    byte[] content = is.readAllBytes();
                    // Handle BOM if present
                    String text = new String(content, StandardCharsets.UTF_8);
                    if (text.startsWith("\uFEFF")) {
                        text = text.substring(1);
                    }
                    return text;
                }
            }
        } catch (Exception e) {
            log.error("Failed to read subtitle: {}", subtitlePath, e);
            throw new IOException("Failed to read subtitle: " + e.getMessage(), e);
        }
    }

    public record FileEntry(String name, String path, boolean isDirectory, boolean isVideo, boolean hasSubtitle,
            boolean isSubtitle, String language) {
    }
}

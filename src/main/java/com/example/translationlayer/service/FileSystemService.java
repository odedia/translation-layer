package com.example.translationlayer.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Interface for file system operations.
 * Implemented by LocalFileService (local disk) and SmbService (NAS).
 */
public interface FileSystemService {

    /**
     * Check if the service is properly configured.
     */
    boolean isConfigured();

    /**
     * Test the connection/access.
     * 
     * @return null if successful, error message if failed
     */
    String testConnection();

    /**
     * List directory contents at the given path.
     * 
     * @param path Path relative to root (empty string for root)
     * @return List of file/folder entries
     */
    List<SmbService.FileEntry> listDirectory(String path) throws IOException;

    /**
     * Write a subtitle file next to a video.
     * 
     * @param videoPath    Path to the video file
     * @param content      Subtitle content
     * @param languageCode Language code (e.g., "he", "es")
     * @return Path where subtitle was saved
     */
    String writeSubtitle(String videoPath, String content, String languageCode) throws IOException;

    /**
     * Write subtitle content to a specific path.
     * 
     * @param subtitlePath Full path for the subtitle file
     * @param content      Subtitle content
     */
    void writeSubtitleDirect(String subtitlePath, String content) throws IOException;

    /**
     * Read subtitle content from a file.
     * 
     * @param subtitlePath Path to the subtitle file
     * @return Subtitle content
     */
    String readSubtitleContent(String subtitlePath) throws IOException;

    /**
     * Download a file to a temporary local file.
     * Used for FFmpeg analysis of embedded subtitles.
     * 
     * @param remotePath Path to file
     * @return Path to local temp file (caller must delete when done)
     */
    Path downloadToTempFile(String remotePath) throws IOException;

    /**
     * Download file header (first ~20MB) for metadata analysis.
     * 
     * @param remotePath Path to file
     * @return Path to local temp file containing header
     */
    Path downloadHeaderToTempFile(String remotePath) throws IOException;

    /**
     * Extract video title from path for subtitle search.
     * 
     * @param videoPath Path to video
     * @return Cleaned title for search
     */
    String extractVideoTitle(String videoPath);
}

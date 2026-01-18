package com.example.translationlayer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service for batch translating all videos in a folder.
 */
@Service
public class BatchTranslationService {

    private static final Logger log = LoggerFactory.getLogger(BatchTranslationService.class);

    private final SmbService smbService;
    private final FfmpegService ffmpegService;
    private final SubtitleService subtitleService;

    // Batch state
    private volatile BatchProgress currentBatch = null;
    private final AtomicBoolean batchRunning = new AtomicBoolean(false);

    public BatchTranslationService(SmbService smbService, FfmpegService ffmpegService,
            SubtitleService subtitleService) {
        this.smbService = smbService;
        this.ffmpegService = ffmpegService;
        this.subtitleService = subtitleService;
    }

    /**
     * Represents a video found during analysis.
     */
    public record VideoToTranslate(
            String path,
            String fileName,
            int trackIndex,
            String language) {
    }

    /**
     * Represents the overall batch progress.
     */
    public record BatchProgress(
            String batchId,
            String folderPath,
            List<VideoToTranslate> videos,
            int totalVideos,
            int completedVideos,
            String currentVideo,
            Instant startTime,
            BatchStatus status,
            String error) {

        public int progressPercent() {
            if (totalVideos == 0)
                return 0;
            return (int) ((completedVideos * 100.0) / totalVideos);
        }
    }

    public enum BatchStatus {
        ANALYZING, // Scanning for videos
        TRANSLATING, // Translating videos
        COMPLETED, // All done
        FAILED, // Error occurred
        CANCELLED // User cancelled
    }

    /**
     * Analyze a folder to find videos with English embedded subtitles.
     * This is a fast operation that only downloads headers.
     */
    public BatchProgress analyzeFolder(String folderPath) throws IOException {
        if (batchRunning.get()) {
            throw new IllegalStateException("A batch translation is already in progress");
        }

        log.info("Analyzing folder for batch translation: {}", folderPath);
        String batchId = "batch_" + System.currentTimeMillis();

        currentBatch = new BatchProgress(
                batchId, folderPath, new ArrayList<>(), 0, 0, "Scanning...",
                Instant.now(), BatchStatus.ANALYZING, null);

        List<VideoToTranslate> videos = new ArrayList<>();

        // Recursively find all video files
        List<String> videoFiles = findVideoFilesRecursive(folderPath);
        log.info("Found {} video files in folder", videoFiles.size());

        // Check each video for English subtitles (header download only)
        for (String videoPath : videoFiles) {
            Path tempFile = null;
            try {
                String fileName = Path.of(videoPath).getFileName().toString();
                currentBatch = new BatchProgress(
                        batchId, folderPath, videos, videoFiles.size(), 0,
                        "Scanning: " + fileName, Instant.now(), BatchStatus.ANALYZING, null);

                // Download only header to check for subtitles
                tempFile = smbService.downloadHeaderToTempFile(videoPath);
                var tracks = ffmpegService.getSubtitleTracks(tempFile);

                // Find first English track
                var englishTrack = tracks.stream()
                        .filter(t -> ffmpegService.isEnglish(t.language()))
                        .findFirst();

                if (englishTrack.isPresent()) {
                    videos.add(new VideoToTranslate(
                            videoPath,
                            fileName,
                            englishTrack.get().index(),
                            englishTrack.get().language()));
                    log.info("Found English subtitle in: {}", fileName);
                } else {
                    log.debug("No English subtitle in: {}", fileName);
                }
            } catch (Exception e) {
                log.warn("Failed to analyze {}: {}", videoPath, e.getMessage());
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        currentBatch = new BatchProgress(
                batchId, folderPath, videos, videos.size(), 0, null,
                Instant.now(), BatchStatus.ANALYZING, null);

        log.info("Analysis complete: {} videos with English subtitles out of {} total",
                videos.size(), videoFiles.size());

        return currentBatch;
    }

    /**
     * Start batch translation of analyzed videos.
     */
    public void startBatchTranslation(String targetLanguage) {
        if (currentBatch == null || currentBatch.videos().isEmpty()) {
            throw new IllegalStateException("No videos to translate. Run analysis first.");
        }
        if (batchRunning.getAndSet(true)) {
            throw new IllegalStateException("Batch translation already in progress");
        }

        // Run in background thread
        new Thread(() -> {
            try {
                runBatchTranslation(targetLanguage);
            } catch (Exception e) {
                log.error("Batch translation failed", e);
                currentBatch = new BatchProgress(
                        currentBatch.batchId(), currentBatch.folderPath(),
                        currentBatch.videos(), currentBatch.totalVideos(),
                        currentBatch.completedVideos(), null,
                        currentBatch.startTime(), BatchStatus.FAILED, e.getMessage());
            } finally {
                batchRunning.set(false);
            }
        }, "BatchTranslation").start();
    }

    /**
     * Execute the batch translation.
     */
    private void runBatchTranslation(String targetLanguage) {
        List<VideoToTranslate> videos = currentBatch.videos();
        log.info("Starting batch translation of {} videos to {}", videos.size(), targetLanguage);

        int completed = 0;
        for (VideoToTranslate video : videos) {
            if (!batchRunning.get()) {
                log.info("Batch translation cancelled");
                currentBatch = new BatchProgress(
                        currentBatch.batchId(), currentBatch.folderPath(),
                        videos, videos.size(), completed, null,
                        currentBatch.startTime(), BatchStatus.CANCELLED, null);
                return;
            }

            currentBatch = new BatchProgress(
                    currentBatch.batchId(), currentBatch.folderPath(),
                    videos, videos.size(), completed, video.fileName(),
                    currentBatch.startTime(), BatchStatus.TRANSLATING, null);

            Path tempFile = null;
            try {
                log.info("Translating {}/{}: {}", completed + 1, videos.size(), video.fileName());

                // Download full video file
                tempFile = smbService.downloadToTempFile(video.path());

                // Extract subtitle
                String subtitleContent = ffmpegService.extractSubtitle(tempFile, video.trackIndex());

                // Translate (uses progress tracker internally)
                String translatedContent = subtitleService.translateSubtitleContent(
                        subtitleContent, targetLanguage);

                // Save to NAS
                smbService.writeSubtitle(video.path(), translatedContent,
                        getLanguageCode(targetLanguage));

                completed++;
                log.info("Completed {}/{}", completed, videos.size());

            } catch (Exception e) {
                log.error("Failed to translate {}: {}", video.fileName(), e.getMessage());
                // Continue with next video
            } finally {
                // CRITICAL: Clean up temp file to avoid filling storage
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                        log.debug("Cleaned up temp file for: {}", video.fileName());
                    } catch (Exception e) {
                        log.warn("Failed to delete temp file: {}", tempFile);
                    }
                }
            }
        }

        currentBatch = new BatchProgress(
                currentBatch.batchId(), currentBatch.folderPath(),
                videos, videos.size(), completed, null,
                currentBatch.startTime(), BatchStatus.COMPLETED, null);

        log.info("Batch translation completed: {}/{} videos", completed, videos.size());
    }

    /**
     * Get current batch progress.
     */
    public BatchProgress getBatchProgress() {
        return currentBatch;
    }

    /**
     * Cancel running batch.
     */
    public void cancelBatch() {
        batchRunning.set(false);
    }

    /**
     * Check if batch is running.
     */
    public boolean isBatchRunning() {
        return batchRunning.get();
    }

    /**
     * Find all video files recursively in a folder.
     */
    private List<String> findVideoFilesRecursive(String folderPath) throws IOException {
        List<String> videos = new ArrayList<>();
        findVideosRecursive(folderPath, videos);
        return videos;
    }

    private void findVideosRecursive(String path, List<String> videos) throws IOException {
        List<SmbService.FileEntry> entries = smbService.listDirectory(path);
        for (SmbService.FileEntry entry : entries) {
            String fullPath = path.isEmpty() ? entry.name() : path + "/" + entry.name();
            if (entry.isDirectory()) {
                findVideosRecursive(fullPath, videos);
            } else if (isVideoFile(entry.name())) {
                videos.add(fullPath);
            }
        }
    }

    private boolean isVideoFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".mkv") || lower.endsWith(".mp4") ||
                lower.endsWith(".avi") || lower.endsWith(".m4v") ||
                lower.endsWith(".mov") || lower.endsWith(".wmv");
    }

    private String getLanguageCode(String language) {
        return switch (language.toLowerCase()) {
            case "hebrew" -> "he";
            case "spanish" -> "es";
            case "french" -> "fr";
            case "german" -> "de";
            case "italian" -> "it";
            case "portuguese" -> "pt";
            case "russian" -> "ru";
            case "japanese" -> "ja";
            case "korean" -> "ko";
            case "chinese" -> "zh";
            case "arabic" -> "ar";
            default -> language.substring(0, Math.min(2, language.length())).toLowerCase();
        };
    }
}

package com.example.translationlayer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Service for extracting embedded subtitles from video files using FFmpeg.
 * Works with video streams from SMB by processing only the necessary data.
 */
@Service
public class FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegService.class);

    private boolean ffmpegAvailable = false;
    private String ffmpegPath = "ffmpeg";
    private String ffprobePath = "ffprobe";

    @PostConstruct
    public void init() {
        // Check if ffmpeg and ffprobe are available
        ffmpegAvailable = checkCommand("ffmpeg", "-version") && checkCommand("ffprobe", "-version");
        if (ffmpegAvailable) {
            log.info("FFmpeg is available for embedded subtitle extraction");
        } else {
            log.warn("FFmpeg not found - embedded subtitle extraction will be disabled");
        }
    }

    private boolean checkCommand(String command, String arg) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, arg);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check if FFmpeg is available on the system.
     */
    public boolean isAvailable() {
        return ffmpegAvailable;
    }

    /**
     * Get list of subtitle tracks from a video file.
     * Uses ffprobe to analyze the file.
     * 
     * @param videoPath Path to video file (local temp file)
     * @return List of subtitle track info
     */
    public List<SubtitleTrack> getSubtitleTracks(Path videoPath) throws IOException {
        if (!ffmpegAvailable) {
            throw new IOException("FFmpeg not available");
        }

        List<SubtitleTrack> tracks = new ArrayList<>();

        try {
            // Use ffprobe to get stream info in JSON format
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobePath,
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_streams",
                    "-select_streams", "s", // Only subtitle streams
                    videoPath.toString());

            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.waitFor();

            if (exitCode != 0) {
                log.warn("ffprobe exited with code {}", exitCode);
                return tracks;
            }

            // Parse JSON output to find subtitle streams
            tracks = parseSubtitleStreams(output);
            log.info("Found {} subtitle tracks in {}", tracks.size(), videoPath.getFileName());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ffprobe interrupted", e);
        }

        return tracks;
    }

    /**
     * Extract a subtitle track from video to SRT format.
     * 
     * @param videoPath  Path to video file
     * @param trackIndex Index of subtitle track to extract
     * @return SRT content as string
     */
    public String extractSubtitle(Path videoPath, int trackIndex) throws IOException {
        if (!ffmpegAvailable) {
            throw new IOException("FFmpeg not available");
        }

        Path outputPath = Files.createTempFile("subtitle_", ".srt");

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath,
                    "-i", videoPath.toString(),
                    "-map", "0:s:" + trackIndex, // Select specific subtitle stream
                    "-c:s", "srt", // Convert to SRT format
                    "-y", // Overwrite output
                    outputPath.toString());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            // Read output to prevent blocking
            p.getInputStream().readAllBytes();
            int exitCode = p.waitFor();

            if (exitCode != 0) {
                throw new IOException("FFmpeg extraction failed with exit code " + exitCode);
            }

            String content = Files.readString(outputPath, StandardCharsets.UTF_8);
            log.info("Extracted subtitle track {} ({} bytes)", trackIndex, content.length());
            return content;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("FFmpeg interrupted", e);
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    /**
     * Parse ffprobe JSON output to extract subtitle stream info.
     */
    private List<SubtitleTrack> parseSubtitleStreams(String jsonOutput) {
        List<SubtitleTrack> tracks = new ArrayList<>();

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonOutput);
            com.fasterxml.jackson.databind.JsonNode streams = root.path("streams");

            if (streams.isArray()) {
                int subtitleIndex = 0;
                for (com.fasterxml.jackson.databind.JsonNode stream : streams) {
                    String codecType = stream.path("codec_type").asText("");
                    if ("subtitle".equals(codecType)) {
                        // Get language from tags.language
                        String language = stream.path("tags").path("language").asText("");
                        String codecName = stream.path("codec_name").asText("");
                        String title = stream.path("tags").path("title").asText("");

                        if (language.isEmpty()) {
                            language = "und";
                        }

                        tracks.add(new SubtitleTrack(subtitleIndex, language, codecName, title));
                        subtitleIndex++;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse ffprobe JSON output", e);
        }

        return tracks;
    }

    /**
     * Check if a language code represents English.
     */
    public boolean isEnglish(String languageCode) {
        if (languageCode == null)
            return false;
        String lc = languageCode.toLowerCase();
        return lc.equals("en") || lc.equals("eng") || lc.equals("english");
    }

    /**
     * Represents a subtitle track in a video file.
     */
    public record SubtitleTrack(int index, String language, String codec, String title) {

        @com.fasterxml.jackson.annotation.JsonGetter("displayName")
        public String displayName() {
            StringBuilder sb = new StringBuilder();
            if (title != null && !title.isEmpty()) {
                sb.append(title);
            } else if (language != null && !language.isEmpty() && !language.equals("und")) {
                // Use language as display name if no title
                sb.append(getLanguageDisplayName());
            } else {
                sb.append("Track ").append(index + 1);
            }
            return sb.toString();
        }

        @com.fasterxml.jackson.annotation.JsonGetter("languageDisplay")
        public String getLanguageDisplayName() {
            if (language == null || language.isEmpty() || language.equals("und")) {
                return "Unknown";
            }
            // Convert language codes to readable names
            return switch (language.toLowerCase()) {
                case "eng", "en" -> "English";
                case "rus", "ru" -> "Russian";
                case "heb", "he" -> "Hebrew";
                case "spa", "es" -> "Spanish";
                case "fra", "fr" -> "French";
                case "deu", "de" -> "German";
                case "ita", "it" -> "Italian";
                case "por", "pt" -> "Portuguese";
                case "jpn", "ja" -> "Japanese";
                case "kor", "ko" -> "Korean";
                case "zho", "zh" -> "Chinese";
                case "ara", "ar" -> "Arabic";
                default -> language.toUpperCase();
            };
        }
    }
}

package com.example.translationlayer.service;

import com.example.translationlayer.model.SubtitleEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and generates SRT and VTT subtitle files.
 */
@Service
public class SubtitleParser {

    private static final Logger log = LoggerFactory.getLogger(SubtitleParser.class);

    // SRT format: index, timestamp line, text lines, blank line
    // NOTE: Do NOT use Pattern.MULTILINE - it makes $ match end-of-line instead of
    // end-of-string,
    // which causes multi-line subtitle text to be truncated
    private static final Pattern SRT_ENTRY_PATTERN = Pattern.compile(
            "(\\d+)\\s*\\n" + // Index
                    "(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s*-->\\s*" + // Start time
                    "(\\d{2}:\\d{2}:\\d{2},\\d{3})\\s*\\n" + // End time
                    "([\\s\\S]*?)(?=\\n\\n|$)"); // Text content - stop at double newline or end

    // VTT format: optional index, timestamp line (with . instead of ,), text lines
    // NOTE: Do NOT use Pattern.MULTILINE - see SRT pattern comment above
    private static final Pattern VTT_ENTRY_PATTERN = Pattern.compile(
            "(?:(\\d+)\\s*\\n)?" + // Optional index
                    "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*" + // Start time (with .)
                    "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s*\\n" + // End time (with .)
                    "([\\s\\S]*?)(?=\\n\\n|$)"); // Text content - stop at double newline or end

    /**
     * Parses an SRT file content into a list of SubtitleEntry objects.
     */
    public List<SubtitleEntry> parseSrt(String content) {
        List<SubtitleEntry> entries = new ArrayList<>();

        // Normalize line endings
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // Remove BOM if present
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        Matcher matcher = SRT_ENTRY_PATTERN.matcher(content);

        while (matcher.find()) {
            try {
                int index = Integer.parseInt(matcher.group(1));
                String startTime = matcher.group(2);
                String endTime = matcher.group(3);
                String text = matcher.group(4).trim();

                // Debug: log multi-line entries
                if (text.contains("\n")) {
                    log.debug("Entry {} has multi-line text ({} lines): {}",
                            index, text.split("\n").length, text.replace("\n", "\\n"));
                }

                entries.add(new SubtitleEntry(index, startTime, endTime, text));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse subtitle entry index: {}", matcher.group(1));
            }
        }

        log.info("Parsed {} subtitle entries from SRT content", entries.size());
        return entries;
    }

    /**
     * Parses a VTT file content into a list of SubtitleEntry objects.
     */
    public List<SubtitleEntry> parseVtt(String content) {
        List<SubtitleEntry> entries = new ArrayList<>();

        // Normalize line endings
        content = content.replace("\r\n", "\n").replace("\r", "\n");

        // Remove WEBVTT header and any metadata
        int headerEnd = content.indexOf("\n\n");
        if (headerEnd > 0 && content.startsWith("WEBVTT")) {
            content = content.substring(headerEnd + 2);
        }

        Matcher matcher = VTT_ENTRY_PATTERN.matcher(content);
        int autoIndex = 1;

        while (matcher.find()) {
            try {
                int index = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : autoIndex++;
                // Convert VTT time format (.) to SRT format (,) for internal consistency
                String startTime = matcher.group(2).replace('.', ',');
                String endTime = matcher.group(3).replace('.', ',');
                String text = matcher.group(4).trim();

                entries.add(new SubtitleEntry(index, startTime, endTime, text));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse VTT subtitle entry");
            }
        }

        log.info("Parsed {} subtitle entries from VTT content", entries.size());
        return entries;
    }

    /**
     * Generates SRT format content from a list of SubtitleEntry objects.
     */
    public String generateSrt(List<SubtitleEntry> entries) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry entry = entries.get(i);
            sb.append(entry.toSrtFormat());
            if (i < entries.size() - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Generates VTT format content from a list of SubtitleEntry objects.
     */
    public String generateVtt(List<SubtitleEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("WEBVTT\n\n");

        for (int i = 0; i < entries.size(); i++) {
            SubtitleEntry entry = entries.get(i);
            sb.append(entry.toVttFormat());
            if (i < entries.size() - 1) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Detects the subtitle format from content.
     */
    public SubtitleFormat detectFormat(String content) {
        if (content.trim().startsWith("WEBVTT")) {
            return SubtitleFormat.VTT;
        }
        return SubtitleFormat.SRT;
    }

    /**
     * Parses subtitle content based on auto-detected format.
     */
    public List<SubtitleEntry> parse(String content) {
        SubtitleFormat format = detectFormat(content);
        return switch (format) {
            case VTT -> parseVtt(content);
            case SRT -> parseSrt(content);
        };
    }

    public enum SubtitleFormat {
        SRT, VTT
    }
}

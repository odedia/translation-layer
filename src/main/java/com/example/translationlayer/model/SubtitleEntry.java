package com.example.translationlayer.model;

/**
 * Represents a single subtitle entry with timing and text.
 */
public record SubtitleEntry(
        int index,
        String startTime,
        String endTime,
        String text) {
    /**
     * Creates a new SubtitleEntry with translated text.
     */
    public SubtitleEntry withTranslatedText(String translatedText) {
        return new SubtitleEntry(index, startTime, endTime, translatedText);
    }

    /**
     * Converts to SRT format string.
     */
    public String toSrtFormat() {
        return String.format("%d\n%s --> %s\n%s\n", index, startTime, endTime, text);
    }

    /**
     * Converts to VTT format string.
     */
    public String toVttFormat() {
        // VTT uses periods instead of commas for milliseconds
        String vttStart = startTime.replace(',', '.');
        String vttEnd = endTime.replace(',', '.');
        return String.format("%s --> %s\n%s\n", vttStart, vttEnd, text);
    }
}

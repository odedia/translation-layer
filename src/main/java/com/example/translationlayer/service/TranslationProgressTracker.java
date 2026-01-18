package com.example.translationlayer.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks translation progress for display on the dashboard.
 */
@Component
public class TranslationProgressTracker {

    private final Map<String, TranslationProgress> activeTranslations = new ConcurrentHashMap<>();

    public record TranslationProgress(
            String fileId,
            String fileName,
            int totalEntries,
            int completedEntries,
            Instant startTime) {

        @JsonProperty("progressPercent")
        public int progressPercent() {
            if (totalEntries == 0)
                return 0;
            return (int) ((completedEntries * 100.0) / totalEntries);
        }
    }

    /**
     * Start tracking a new translation.
     */
    public void startTranslation(String fileId, String fileName, int totalEntries) {
        activeTranslations.put(fileId, new TranslationProgress(
                fileId, fileName, totalEntries, 0, Instant.now()));
    }

    /**
     * Update progress for a translation.
     */
    public void updateProgress(String fileId, int completedEntries) {
        TranslationProgress current = activeTranslations.get(fileId);
        if (current != null) {
            activeTranslations.put(fileId, new TranslationProgress(
                    current.fileId(),
                    current.fileName(),
                    current.totalEntries(),
                    completedEntries,
                    current.startTime()));
        }
    }

    /**
     * Mark a translation as complete.
     */
    public void completeTranslation(String fileId) {
        activeTranslations.remove(fileId);
    }

    /**
     * Get all active translations.
     */
    public Map<String, TranslationProgress> getActiveTranslations() {
        return Map.copyOf(activeTranslations);
    }

    /**
     * Check if a translation is in progress.
     */
    public boolean isInProgress(String fileId) {
        return activeTranslations.containsKey(fileId);
    }
}

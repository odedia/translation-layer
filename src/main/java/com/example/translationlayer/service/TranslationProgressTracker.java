package com.example.translationlayer.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks translation progress for display on the dashboard.
 * Enforces single active translation with pending queue.
 */
@Component
public class TranslationProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(TranslationProgressTracker.class);

    private final Map<String, TranslationProgress> activeTranslations = new ConcurrentHashMap<>();
    private final List<String> pendingQueue = Collections.synchronizedList(new ArrayList<>());
    private final ReentrantLock translationLock = new ReentrantLock();
    private volatile String currentlyTranslating = null;

    public enum TranslationStatus {
        ACTIVE, // Currently being translated
        PENDING, // Waiting in queue
        COMPLETE // Done (briefly shown before removal)
    }

    public record TranslationProgress(
            String fileId,
            String fileName,
            int totalEntries,
            int completedEntries,
            Instant startTime,
            TranslationStatus status) {

        @JsonProperty("progressPercent")
        public int progressPercent() {
            if (totalEntries == 0)
                return 0;
            return (int) ((completedEntries * 100.0) / totalEntries);
        }
    }

    /**
     * Check if we can start a new translation (no other translation active).
     * If not, the caller should wait or queue.
     */
    public boolean canStartTranslation() {
        return currentlyTranslating == null;
    }

    /**
     * Try to acquire the translation lock. Returns true if acquired.
     * If another translation is active, adds this one to pending queue.
     */
    public boolean tryStartTranslation(String fileId, String fileName, int totalEntries) {
        if (translationLock.tryLock()) {
            try {
                if (currentlyTranslating != null) {
                    // Another translation started between check and lock
                    addToPendingQueue(fileId, fileName, totalEntries);
                    return false;
                }
                currentlyTranslating = fileId;
                activeTranslations.put(fileId, new TranslationProgress(
                        fileId, fileName, totalEntries, 0, Instant.now(), TranslationStatus.ACTIVE));
                log.info("Started translation: {} ({})", fileName, fileId);
                return true;
            } finally {
                translationLock.unlock();
            }
        } else {
            addToPendingQueue(fileId, fileName, totalEntries);
            return false;
        }
    }

    /**
     * Add a translation to the pending queue.
     */
    private void addToPendingQueue(String fileId, String fileName, int totalEntries) {
        if (!pendingQueue.contains(fileId)) {
            pendingQueue.add(fileId);
            activeTranslations.put(fileId, new TranslationProgress(
                    fileId, fileName, totalEntries, 0, Instant.now(), TranslationStatus.PENDING));
            log.info("Queued translation: {} ({}) - position {} in queue", fileName, fileId, pendingQueue.size());
        }
    }

    /**
     * Wait for the translation lock (blocking).
     * Use this when we want to queue and wait.
     */
    public void waitForTranslationSlot(String fileId, String fileName, int totalEntries) {
        // First, add to pending queue if not already the active one
        if (currentlyTranslating != null && !currentlyTranslating.equals(fileId)) {
            addToPendingQueue(fileId, fileName, totalEntries);
        }

        // Wait for the lock (blocking call)
        translationLock.lock();
        try {
            currentlyTranslating = fileId;
            pendingQueue.remove(fileId);
            activeTranslations.put(fileId, new TranslationProgress(
                    fileId, fileName, totalEntries, 0, Instant.now(), TranslationStatus.ACTIVE));
            log.info("Started translation (after wait): {} ({})", fileName, fileId);
        } finally {
            // Don't unlock here - unlocked in completeTranslation
        }
    }

    /**
     * Start tracking a new translation. Will wait if another is in progress.
     * This is the backwards-compatible method that blocks until ready.
     */
    public void startTranslation(String fileId, String fileName, int totalEntries) {
        if (!tryStartTranslation(fileId, fileName, totalEntries)) {
            // Wait for our turn
            waitForTranslationSlot(fileId, fileName, totalEntries);
        }
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
                    current.startTime(),
                    current.status()));
        }
    }

    /**
     * Mark a translation as complete.
     */
    public void completeTranslation(String fileId) {
        activeTranslations.remove(fileId);
        pendingQueue.remove(fileId);

        if (fileId.equals(currentlyTranslating)) {
            currentlyTranslating = null;
            // Release lock if we hold it
            if (translationLock.isHeldByCurrentThread()) {
                translationLock.unlock();
            }
            log.info("Completed translation: {}", fileId);
        }
    }

    /**
     * Get all active and pending translations.
     */
    public Map<String, TranslationProgress> getActiveTranslations() {
        return Map.copyOf(activeTranslations);
    }

    /**
     * Get queue position (0 = active, 1+ = pending position).
     */
    public int getQueuePosition(String fileId) {
        if (fileId.equals(currentlyTranslating)) {
            return 0;
        }
        int pos = pendingQueue.indexOf(fileId);
        return pos >= 0 ? pos + 1 : -1;
    }

    /**
     * Check if a translation is in progress.
     */
    public boolean isInProgress(String fileId) {
        return activeTranslations.containsKey(fileId);
    }

    /**
     * Check if any translation is currently active.
     */
    public boolean hasActiveTranslation() {
        return currentlyTranslating != null;
    }

    /**
     * Get count of pending translations.
     */
    public int getPendingCount() {
        return pendingQueue.size();
    }
}

package com.example.translationlayer.service;

import com.example.translationlayer.config.AppSettings;
import com.example.translationlayer.config.LanguageConfig;
import com.example.translationlayer.model.SubtitleEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Translation service using Ollama with Gemma model.
 * Configured to produce exact translations without any chattiness.
 */
@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final ChatClient.Builder chatClientBuilder;
    private final RtlTextProcessor rtlTextProcessor;
    private final LanguageConfig languageConfig;
    private final AppSettings appSettings;
    private ExecutorService executorService;

    @Value("${translation.source-language:English}")
    private String sourceLanguage;

    @Value("${spring.ai.model.chat:ollama}")
    private String chatProvider;

    // Auto-tuned based on provider
    private int batchSize;
    private int parallelThreads;

    // Pattern to detect hearing impaired annotations like [music playing] or (door
    // slams)
    private static final Pattern HEARING_IMPAIRED_PATTERN = Pattern.compile("^\\s*[\\[\\(][^\\]\\)]+[\\]\\)]\\s*$");

    // Patterns to detect and remove chatty prefixes
    private static final Pattern[] CHATTY_PATTERNS = {
            Pattern.compile("^(?:Here(?:'s| is) (?:the )?translation:?)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:Translation:?)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:The translation is:?)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:In Hebrew:?)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:Hebrew:?)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:Translated text:?)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:Output:?)\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^```(?:hebrew)?\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s*```$", Pattern.CASE_INSENSITIVE),
    };

    public TranslationService(ChatClient.Builder chatClientBuilder, RtlTextProcessor rtlTextProcessor,
            LanguageConfig languageConfig, AppSettings appSettings) {
        this.chatClientBuilder = chatClientBuilder;
        this.rtlTextProcessor = rtlTextProcessor;
        this.languageConfig = languageConfig;
        this.appSettings = appSettings;
        this.executorService = null; // Lazy init
    }

    @jakarta.annotation.PostConstruct
    private void init() {
        // Auto-tune based on provider
        boolean isOpenAI = "openai".equalsIgnoreCase(chatProvider);
        // OpenAI: high concurrency; Ollama: moderate (can adjust based on hardware)
        this.batchSize = isOpenAI ? 50 : 20;
        this.parallelThreads = isOpenAI ? 8 : 6;
        log.info("Using {} provider - batch size: {}, threads: {}", chatProvider, batchSize, parallelThreads);
    }

    private synchronized ExecutorService getExecutor() {
        if (executorService == null) {
            executorService = Executors.newFixedThreadPool(parallelThreads);
        }
        return executorService;
    }

    /**
     * Build a dynamic system prompt based on the current target language.
     */
    private String buildSystemPrompt() {
        String targetLang = languageConfig.getTargetLanguage();
        boolean isRtl = languageConfig.isCurrentLanguageRtl();

        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("""
                You are a professional subtitle translator translating to %s.

                CRITICAL RULES - FOLLOW EXACTLY:
                1. COMPLETE TRANSLATION - Translate EVERYTHING between [[[ and ]]] delimiters
                2. The symbol || represents a line break - keep it as || in your output
                3. Do NOT skip, summarize, or shorten ANY content
                4. Output ONLY the translated %s text, nothing else
                5. No greetings, explanations, "Translation:", quotes, or markdown
                6. Keep any HTML tags like <i> or <b> exactly as-is
                """, targetLang, targetLang));

        if (isRtl) {
            prompt.append(String.format("""

                    %s RTL RULES:
                    - %s is written RIGHT-TO-LEFT
                    - Punctuation (. , ! ? : ;) appears at END of sentence
                    - Numbers stay LTR but integrate naturally
                    """, targetLang.toUpperCase(), targetLang));
        }

        return prompt.toString();
    }

    /**
     * Translates a single text string from source to target language.
     * Translates full text together for context, then enforces line count matching.
     */
    public String translateText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // Skip hearing impaired annotations if setting is enabled
        if (appSettings.isSkipHearingImpaired() && isHearingImpairedOnly(text)) {
            log.debug("Skipping hearing impaired text: {}", text);
            return text; // Return original text unchanged
        }

        // Count original lines for structure preservation
        String[] originalLines = text.split("\n", -1);
        int originalLineCount = originalLines.length;

        try {
            String prompt = buildTranslationPrompt(text);

            // Debug: log what we're sending
            log.debug("Translation input ({} lines, {} chars): {}",
                    originalLineCount, text.length(), text.replace("\n", "\\n"));
            log.debug("Prompt being sent: {}", prompt);

            // Build chat client with dynamic system prompt for current language
            ChatClient chatClient = chatClientBuilder
                    .defaultSystem(buildSystemPrompt())
                    .build();

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Debug: log what we received
            log.debug("Translation response ({} chars): {}",
                    response != null ? response.length() : 0,
                    response != null ? response.replace("\n", "\\n") : "null");

            // Aggressively clean up the response
            String cleaned = cleanTranslationResponse(response, text);

            // Enforce line count matching
            cleaned = enforceLineCount(cleaned, originalLineCount);

            // Apply RTL text processing for Hebrew
            return rtlTextProcessor.processRtlText(cleaned);
        } catch (Exception e) {
            log.error("Translation failed for text: {}", text, e);
            return text; // Return original text on failure
        }
    }

    /**
     * Ensures the translated text has the same number of lines as the original.
     * If too few lines, splits at natural break points. If too many, joins.
     */
    private String enforceLineCount(String text, int targetLineCount) {
        if (targetLineCount <= 1) {
            // Single line - just remove any newlines
            return text.replace("\n", " ").trim();
        }

        String[] lines = text.split("\n", -1);

        if (lines.length == targetLineCount) {
            // Already correct
            return text;
        }

        if (lines.length > targetLineCount) {
            // Too many lines - join excess lines with spaces
            StringBuilder result = new StringBuilder();
            int linesPerTarget = lines.length / targetLineCount;
            int remainder = lines.length % targetLineCount;
            int lineIndex = 0;

            for (int i = 0; i < targetLineCount; i++) {
                if (i > 0)
                    result.append("\n");
                int linesToJoin = linesPerTarget + (i < remainder ? 1 : 0);
                StringBuilder joined = new StringBuilder();
                for (int j = 0; j < linesToJoin && lineIndex < lines.length; j++) {
                    if (j > 0)
                        joined.append(" ");
                    joined.append(lines[lineIndex++].trim());
                }
                result.append(joined);
            }
            return result.toString();
        }

        // Too few lines - split at natural break points
        String joined = String.join(" ", lines).trim();
        int approxCharsPerLine = joined.length() / targetLineCount;

        StringBuilder result = new StringBuilder();
        int pos = 0;

        for (int i = 0; i < targetLineCount - 1; i++) {
            if (i > 0)
                result.append("\n");

            // Find a good break point near the target position
            int targetPos = Math.min(pos + approxCharsPerLine, joined.length() - 1);
            int breakPoint = findBreakPoint(joined, targetPos, pos);

            result.append(joined.substring(pos, breakPoint).trim());
            pos = breakPoint;
        }

        // Add remaining text as last line
        if (pos < joined.length()) {
            if (result.length() > 0)
                result.append("\n");
            result.append(joined.substring(pos).trim());
        }

        return result.toString();
    }

    /**
     * Find a natural break point (space) near the target position.
     */
    private int findBreakPoint(String text, int target, int minPos) {
        // Look for a space within +/- 15 characters of target
        for (int i = target; i < Math.min(target + 15, text.length()); i++) {
            if (text.charAt(i) == ' ') {
                return i + 1;
            }
        }
        for (int i = target; i > Math.max(target - 15, minPos); i--) {
            if (text.charAt(i) == ' ') {
                return i + 1;
            }
        }
        return Math.min(target + 1, text.length());
    }

    /**
     * Translates a list of subtitle entries with progress callback.
     * 
     * @param entries          the entries to translate
     * @param progressCallback called after each batch with the count of completed
     *                         entries (can be null)
     */
    public List<SubtitleEntry> translateSubtitles(List<SubtitleEntry> entries,
            java.util.function.IntConsumer progressCallback) {
        // Use configurable batch size from settings
        int configuredBatchSize = appSettings.getTranslationBatchSize();
        log.info("Starting translation of {} subtitle entries with batch size {}", entries.size(), configuredBatchSize);

        List<SubtitleEntry> translatedEntries = new ArrayList<>();

        // Process in batches for better performance and context
        for (int i = 0; i < entries.size(); i += configuredBatchSize) {
            int end = Math.min(i + configuredBatchSize, entries.size());
            List<SubtitleEntry> batch = entries.subList(i, end);

            List<SubtitleEntry> translatedBatch = translateBatch(batch);
            translatedEntries.addAll(translatedBatch);

            log.info("Translated batch {}/{}", (i / configuredBatchSize) + 1,
                    (entries.size() + configuredBatchSize - 1) / configuredBatchSize);

            // Report progress
            if (progressCallback != null) {
                progressCallback.accept(translatedEntries.size());
            }
        }

        log.info("Completed translation of {} subtitle entries", translatedEntries.size());
        return translatedEntries;
    }

    /**
     * Translates a list of subtitle entries (without progress callback).
     */
    public List<SubtitleEntry> translateSubtitles(List<SubtitleEntry> entries) {
        return translateSubtitles(entries, null);
    }

    /**
     * Translates a batch of subtitle entries together for better context.
     * Uses <<~N~>> markers to identify each cue in the batch.
     */
    private List<SubtitleEntry> translateBatch(List<SubtitleEntry> batch) {
        if (batch.isEmpty()) {
            return batch;
        }

        // Build batch prompt with numbered markers
        StringBuilder batchPrompt = new StringBuilder();
        batchPrompt.append("Translate these subtitles to ").append(languageConfig.getTargetLanguage());
        batchPrompt.append(". Preserve the <<~N~>> markers exactly. Output ONLY the translations with markers.\n\n");

        for (int i = 0; i < batch.size(); i++) {
            SubtitleEntry entry = batch.get(i);
            String text = entry.text().replace("\n", " "); // Flatten multi-line into single line for input

            // Skip hearing impaired entries if setting is enabled
            if (appSettings.isSkipHearingImpaired() && isHearingImpairedOnly(entry.text())) {
                batchPrompt.append("<<~").append(i).append("~>> ").append(text).append("\n");
            } else {
                batchPrompt.append("<<~").append(i).append("~>> ").append(text).append("\n");
            }
        }

        try {
            // Build chat client with dynamic system prompt for current language
            ChatClient chatClient = chatClientBuilder
                    .defaultSystem(buildSystemPrompt())
                    .build();

            String response = chatClient.prompt()
                    .user(batchPrompt.toString())
                    .call()
                    .content();

            log.debug("Batch translation response: {}", response);

            // Parse response and match to original entries
            return parseBatchResponse(response, batch);

        } catch (Exception e) {
            log.error("Batch translation failed, falling back to individual translation", e);
            // Fallback: translate individually
            return translateBatchIndividually(batch);
        }
    }

    /**
     * Parse the batch translation response and match translated text to original
     * entries.
     */
    private List<SubtitleEntry> parseBatchResponse(String response, List<SubtitleEntry> batch) {
        List<SubtitleEntry> result = new ArrayList<>();

        // Pattern to match <<~N~>> followed by translated text
        Pattern markerPattern = Pattern.compile("<<~(\\d+)~>>\\s*(.+?)(?=<<~\\d+~>>|$)", Pattern.DOTALL);
        java.util.regex.Matcher matcher = markerPattern.matcher(response);

        // Build a map of index -> translated text
        java.util.Map<Integer, String> translations = new java.util.HashMap<>();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String translatedText = matcher.group(2).trim();
            // Clean up the translated text
            translatedText = cleanTranslationResponse(translatedText, "");
            translations.put(index, translatedText);
        }

        // Map translations back to entries
        for (int i = 0; i < batch.size(); i++) {
            SubtitleEntry entry = batch.get(i);
            String translatedText = translations.get(i);

            if (translatedText != null && !translatedText.isBlank()) {
                // Enforce line count to match original structure
                int originalLineCount = entry.text().split("\n", -1).length;
                if (originalLineCount > 1) {
                    translatedText = enforceLineCount(translatedText, originalLineCount);
                }
                // Apply RTL processing
                translatedText = rtlTextProcessor.processRtlText(translatedText);
                result.add(entry.withTranslatedText(translatedText));
            } else {
                // If no translation found, keep original or translate individually
                log.warn("No translation found for cue {}, using original", i);
                result.add(entry);
            }
        }

        return result;
    }

    /**
     * Fallback: translate entries individually if batch translation fails.
     */
    private List<SubtitleEntry> translateBatchIndividually(List<SubtitleEntry> batch) {
        List<CompletableFuture<SubtitleEntry>> futures = batch.stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> {
                    String translatedText = translateText(entry.text());
                    return entry.withTranslatedText(translatedText);
                }, getExecutor()))
                .collect(Collectors.toList());

        return futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());
    }

    private String buildTranslationPrompt(String text) {
        String targetLanguage = languageConfig.getTargetLanguage();
        // Use explicit delimiters so the model sees the full input as one unit
        // Replace internal newlines with a marker to prevent confusion
        String markedText = text.replace("\n", " || ");
        return String.format("Translate %s to %s. Text: [[[%s]]]", sourceLanguage, targetLanguage, markedText);
    }

    /**
     * Aggressively cleans up the translation response.
     */
    private String cleanTranslationResponse(String response, String originalText) {
        if (response == null) {
            return "";
        }

        String cleaned = response.trim();

        // Remove any chatty prefixes
        for (Pattern pattern : CHATTY_PATTERNS) {
            cleaned = pattern.matcher(cleaned).replaceFirst("");
        }

        // Remove delimiter brackets if model included them (handle all variations)
        // Triple brackets first, then double, then single
        cleaned = cleaned.replace("[[[", "").replace("]]]", "");
        cleaned = cleaned.replace("[[", "").replace("]]", "");
        // Only remove single brackets at start/end (not internal ones like [music])
        if (cleaned.startsWith("[") && !cleaned.contains("]")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("]") && cleaned.lastIndexOf("[") < cleaned.length() - 10) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        // Remove quotes if the entire response is quoted
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
                (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        // Remove backticks (markdown code blocks)
        cleaned = cleaned.replace("`", "");

        // Convert || markers back to newlines
        cleaned = cleaned.replace(" || ", "\n").replace("||", "\n");

        return cleaned.trim();
    }

    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * Check if text contains only hearing impaired annotations (e.g., [music
     * playing]).
     */
    private boolean isHearingImpairedOnly(String text) {
        // Check each line - if ALL lines are hearing impaired annotations, skip
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !HEARING_IMPAIRED_PATTERN.matcher(trimmed).matches()) {
                return false; // At least one line is not a HI annotation
            }
        }
        return true; // All non-empty lines are HI annotations
    }
}

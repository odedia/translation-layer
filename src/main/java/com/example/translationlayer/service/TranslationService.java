package com.example.translationlayer.service;

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
    private ExecutorService executorService;

    @Value("${translation.source-language:English}")
    private String sourceLanguage;

    @Value("${spring.ai.model.chat:ollama}")
    private String chatProvider;

    // Auto-tuned based on provider
    private int batchSize;
    private int parallelThreads;

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
            LanguageConfig languageConfig) {
        this.chatClientBuilder = chatClientBuilder;
        this.rtlTextProcessor = rtlTextProcessor;
        this.languageConfig = languageConfig;
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

                CRITICAL RULES:
                - Output ONLY the translated %s text, nothing else
                - NO greetings, NO explanations, NO notes, NO formatting markers
                - NO phrases like "Here's the translation:" or "Translation:"
                - PRESERVE LINE BREAKS EXACTLY: if input has 2 lines, output must have 2 lines
                - Each line in the input corresponds to one line in the output
                - Do NOT merge multiple lines into one line
                - Do NOT wrap output in quotes or markdown
                - If you see HTML tags like <i> or <b>, preserve them exactly
                """, targetLang, targetLang));

        if (isRtl) {
            prompt.append(String.format("""

                    %s RTL RULES (VERY IMPORTANT):
                    - %s is written RIGHT-TO-LEFT
                    - Punctuation marks (. , ! ? : ;) must appear at the END of the sentence
                    - Numbers stay in their original LTR order but integrate naturally
                    - The comma between clauses goes AFTER the word, not before
                    """, targetLang.toUpperCase(), targetLang));
        }

        return prompt.toString();
    }

    /**
     * Translates a single text string from source to target language.
     */
    public String translateText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        try {
            String prompt = buildTranslationPrompt(text);

            // Build chat client with dynamic system prompt for current language
            ChatClient chatClient = chatClientBuilder
                    .defaultSystem(buildSystemPrompt())
                    .build();

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Aggressively clean up the response
            String cleaned = cleanTranslationResponse(response, text);

            // Apply RTL text processing for Hebrew
            return rtlTextProcessor.processRtlText(cleaned);
        } catch (Exception e) {
            log.error("Translation failed for text: {}", text, e);
            return text; // Return original text on failure
        }
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
        log.info("Starting translation of {} subtitle entries with batch size {}", entries.size(), batchSize);

        List<SubtitleEntry> translatedEntries = new ArrayList<>();

        // Process in batches for better performance
        for (int i = 0; i < entries.size(); i += batchSize) {
            int end = Math.min(i + batchSize, entries.size());
            List<SubtitleEntry> batch = entries.subList(i, end);

            List<SubtitleEntry> translatedBatch = translateBatch(batch);
            translatedEntries.addAll(translatedBatch);

            log.info("Translated batch {}/{}", (i / batchSize) + 1,
                    (entries.size() + batchSize - 1) / batchSize);

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
     * Translates a batch of subtitle entries in parallel.
     */
    private List<SubtitleEntry> translateBatch(List<SubtitleEntry> batch) {
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
        return String.format("%s → %s:\n%s", sourceLanguage, targetLanguage, text);
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

        // Remove quotes if the entire response is quoted
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
                (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        // Remove backticks (markdown code blocks)
        cleaned = cleaned.replace("`", "");

        // Preserve original line count - if original had N lines, output should have N
        // lines
        int originalLineCount = originalText.split("\n", -1).length;
        String[] translatedLines = cleaned.split("\n", -1);

        if (translatedLines.length > originalLineCount) {
            // Model added extra lines - take only what we need
            cleaned = String.join("\n", java.util.Arrays.copyOf(translatedLines, originalLineCount));
        }

        return cleaned.trim();
    }

    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executorService.shutdown();
    }
}

package com.example.translationlayer.service;

import com.example.translationlayer.config.LanguageConfig;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processor for fixing RTL (Right-to-Left) text issues.
 * Supports Hebrew, Arabic, Persian, and Urdu.
 * Uses Unicode bidirectional control characters for proper display.
 */
@Component
public class RtlTextProcessor {

    private final LanguageConfig languageConfig;

    // Unicode Bidirectional Control Characters
    private static final char RLM = '\u200F'; // Right-to-Left Mark
    private static final char LRM = '\u200E'; // Left-to-Right Mark
    private static final char RLE = '\u202B'; // Right-to-Left Embedding
    private static final char PDF = '\u202C'; // Pop Directional Formatting

    // Pattern to match numbers (including decimals, commas, percentages, times)
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "([\\$€£¥₪]?[+-]?\\d+(?:[,.]\\d+)*(?::\\d+)?%?)");

    // Pattern to match standalone punctuation
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile(
            "([.!?,:;])(?=\\s|$)");

    // Pattern to detect Hebrew characters (U+0590 to U+05FF)
    private static final Pattern HEBREW_PATTERN = Pattern.compile("[\\u0590-\\u05FF]");

    // Pattern to detect Arabic characters (U+0600 to U+06FF, U+0750 to U+077F,
    // U+08A0 to U+08FF)
    private static final Pattern ARABIC_PATTERN = Pattern.compile("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]");

    // Pattern to match parenthetical content
    private static final Pattern PARENTHETICAL_PATTERN = Pattern.compile(
            "([\\(\\[\"'])([^\\)\\]\"']+)([\\)\\]\"'])");

    public RtlTextProcessor(LanguageConfig languageConfig) {
        this.languageConfig = languageConfig;
    }

    /**
     * Process translated text to fix RTL display issues.
     * Only processes if the current target language is RTL.
     */
    public String processRtlText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // Only process if target language is RTL
        if (!languageConfig.isCurrentLanguageRtl()) {
            return text;
        }

        // Check if text contains RTL characters
        if (!containsRtlCharacters(text)) {
            return text;
        }

        // Process each line separately
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append("\n");
            }
            result.append(processLine(lines[i]));
        }

        return result.toString();
    }

    /**
     * Check if text contains RTL characters (Hebrew or Arabic script).
     */
    private boolean containsRtlCharacters(String text) {
        return HEBREW_PATTERN.matcher(text).find() || ARABIC_PATTERN.matcher(text).find();
    }

    /**
     * Process a single line of RTL text.
     */
    private String processLine(String line) {
        if (line.isBlank()) {
            return line;
        }

        String processed = line;

        // Step 1: Wrap numbers with LRM to preserve LTR order
        processed = wrapNumbers(processed);

        // Step 2: Fix punctuation placement
        processed = fixPunctuation(processed);

        // Step 3: Handle parenthetical/quoted content
        processed = handleParentheticals(processed);

        // Step 4: Wrap entire line in RLE...PDF for RTL base direction
        processed = RLE + "" + RLM + processed + PDF;

        return processed;
    }

    /**
     * Wrap numbers with LRM markers to preserve left-to-right order.
     */
    private String wrapNumbers(String text) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String number = matcher.group(1);
            String replacement = LRM + number + LRM;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Fix punctuation placement in RTL context.
     */
    private String fixPunctuation(String text) {
        Matcher matcher = PUNCTUATION_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String punct = matcher.group(1);
            matcher.appendReplacement(sb, RLM + punct);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Handle parenthetical or quoted content.
     */
    private String handleParentheticals(String text) {
        Matcher matcher = PARENTHETICAL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String open = matcher.group(1);
            String content = matcher.group(2);
            String close = matcher.group(3);

            if (!containsRtlCharacters(content)) {
                // LTR content - wrap with LRM
                String replacement = open + LRM + content + LRM + close;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                // RTL content - add RLM for proper bracket orientation
                String replacement = RLM + open + content + close + RLM;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(sb);

        return sb.toString();
    }
}

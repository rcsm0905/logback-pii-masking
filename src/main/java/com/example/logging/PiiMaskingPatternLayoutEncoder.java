package com.example.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A custom Logback encoder that:
 *  - Locates a JSON object substring in the formatted message.
 *  - Parses it with Jackson.
 *  - Masks values for keys listed in maskKeys (comma-separated).
 *  - Replaces the original JSON substring with the masked JSON.
 *  - Delegates final formatting to a PatternLayout using the configured pattern.
 *
 * Configure in logback.xml:
 *
 * <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
 *   <encoder class="com.example.logging.PiiMaskingPatternLayoutEncoder">
 *     <pattern>%d %-5level [%thread] %logger{0}: %msg%n</pattern>
 *     <maskKeys>NAME,NAME_CN,NAME_CN_RAW,ID_NUMBER,DATE_OF_BIRTH,STANDARDIZED_DATE_OF_BIRTH,LATEST_ISSUE_DATE,STANDARDIZED_LATEST_ISSUE_DATE,CHINESE_COMMERCIAL_CODE,SYMBOLS</maskKeys>
 *     <maskText>****</maskText>
 *     <charset>UTF-8</charset>
 *   </encoder>
 * </appender>
 */
public class PiiMaskingPatternLayoutEncoder extends EncoderBase<ILoggingEvent> {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiiJsonMasker jsonMasker = new PiiJsonMasker(objectMapper);

    private PatternLayout layout;
    private String pattern = "%msg%n";
    private String maskKeys = ""; // comma-separated keys
    private String maskText = "****";
    private Charset charset = StandardCharsets.UTF_8;
    private Set<String> piiKeysSet = Collections.emptySet();

    @Override
    public void start() {
        if (this.layout == null) {
            this.layout = new PatternLayout();
            this.layout.setContext(getContext());
            this.layout.setPattern(pattern);
            this.layout.start();
        }
        this.piiKeysSet = parseKeys(maskKeys);
        super.start();
    }

    @Override
    public byte[] headerBytes() {
        return null;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        try {
            String original = event.getFormattedMessage();
            String maskedMsg = maskMessageIfJsonPresent(original);

            // Wrap the event so PatternLayout will see our masked message
            ILoggingEvent wrapped = new MaskedLoggingEvent(event, maskedMsg);

            String formatted = layout.doLayout(wrapped);
            return formatted.getBytes(charset);
        } catch (Exception e) {
            // On any failure, fall back to original layout with original event.
            String fallback = layout.doLayout(event);
            return fallback.getBytes(charset);
        }
    }

    @Override
    public byte[] footerBytes() {
        return null;
    }

    // ----------------- Configuration setters -----------------

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public void setMaskKeys(String maskKeys) {
        this.maskKeys = maskKeys;
        this.piiKeysSet = parseKeys(maskKeys);
    }

    public void setMaskText(String maskText) {
        this.maskText = maskText;
    }

    public void setCharset(String charsetName) {
        if (charsetName != null && !charsetName.isBlank()) {
            this.charset = Charset.forName(charsetName);
        }
    }

    // ----------------- Internals -----------------

    private Set<String> parseKeys(String keysCsv) {
        if (keysCsv == null || keysCsv.isBlank()) return Collections.emptySet();
        return Arrays.stream(keysCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private String maskMessageIfJsonPresent(String formattedMessage) {
        if (formattedMessage == null) return null;

        int firstBrace = formattedMessage.indexOf('{');
        if (firstBrace < 0) {
            // No JSON object in the message, return as-is.
            return formattedMessage;
        }

        int[] bounds = findTopLevelJsonObjectBounds(formattedMessage, firstBrace);
        if (bounds == null) {
            return formattedMessage; // Could not find a balanced JSON object
        }

        int start = bounds[0];
        int end = bounds[1]; // inclusive index of matching closing brace

        String prefix = formattedMessage.substring(0, start);
        String json = formattedMessage.substring(start, end + 1);
        String suffix = formattedMessage.substring(end + 1);

        try {
            String maskedJson = jsonMasker.maskJsonObjectString(json, piiKeysSet, maskText);
            return prefix + maskedJson + suffix;
        } catch (Exception ex) {
            // If JSON parse/mask fails, return original
            return formattedMessage;
        }
    }

    /**
     * Finds the bounds [start, end] of the top-level balanced JSON object starting at startIndex.
     * Handles nested braces and strings with escaped quotes.
     */
    private static int[] findTopLevelJsonObjectBounds(String s, int startIndex) {
        int len = s.length();
        int i = startIndex;
        if (i >= len || s.charAt(i) != '{') return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (; i < len; i++) {
            char c = s.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false; // consume escaped char
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new int[]{startIndex, i};
                }
            }
        }
        return null; // no matching closing brace
    }
}
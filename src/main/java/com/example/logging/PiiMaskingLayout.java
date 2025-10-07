package com.example.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Alternative Layout-based approach for PII masking.
 * This is simpler than the Encoder approach and directly overrides doLayout.
 * 
 * Usage in logback-spring.xml:
 * <layout class="com.example.logging.PiiMaskingLayout">
 *   <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{0}: %msg%n</pattern>
 *   <maskKeys>NAME,ID_NUMBER,SSN,EMAIL</maskKeys>
 *   <maskText>****</maskText>
 * </layout>
 */
public class PiiMaskingLayout extends PatternLayout {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PiiJsonMasker jsonMasker = new PiiJsonMasker(objectMapper);
    
    private String maskKeys = ""; // comma-separated keys
    private String maskText = "****";
    private Set<String> piiKeysSet = Collections.emptySet();

    @Override
    public void start() {
        this.piiKeysSet = parseKeys(maskKeys);
        super.start();
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        try {
            // Get the original formatted message
            String originalMessage = event.getFormattedMessage();
            
            // Mask PII in the message
            String maskedMessage = maskMessageIfJsonPresent(originalMessage);
            
            // Create a new event with the masked message
            ILoggingEvent maskedEvent = new MaskedLoggingEvent(event, maskedMessage);
            
            // Use the parent PatternLayout to format the masked event
            return super.doLayout(maskedEvent);
            
        } catch (Exception e) {
            // If masking fails, fall back to original layout
            return super.doLayout(event);
        }
    }

    // Configuration setters
    public void setMaskKeys(String maskKeys) {
        this.maskKeys = maskKeys;
        this.piiKeysSet = parseKeys(maskKeys);
    }

    public void setMaskText(String maskText) {
        this.maskText = maskText;
    }

    // Helper methods (same as in PiiMaskingPatternLayoutEncoder)
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
            return formattedMessage; // No JSON object in the message
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

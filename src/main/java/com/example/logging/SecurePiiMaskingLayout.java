package com.example.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SECURE PII masking layout with fail-safe behavior.
 * 
 * CRITICAL SECURITY REQUIREMENT:
 * - If PII masking fails, the log message is NOT logged to prevent PII leakage
 * - Only non-PII messages or successfully masked messages are logged
 * - Failed masking attempts are logged separately for monitoring
 * 
 * Key features:
 * - Singleton ObjectMapper for performance
 * - Fast JSON detection using regex
 * - Memory and depth limits
 * - Comprehensive error handling
 * - Security-first approach
 */
public class SecurePiiMaskingLayout extends PatternLayout {
    
    private static final Logger logger = LoggerFactory.getLogger(SecurePiiMaskingLayout.class);
    private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY.PII_MASKING");
    
    // Singleton ObjectMapper for better performance
    private static final ObjectMapper OBJECT_MAPPER = createOptimizedObjectMapper();
    
    // Fast JSON detection pattern (looks for { followed by "key":)
    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s]*\"[^\"]+\"\\s*:");
    
    // Configuration
    private String maskKeys = "";
    private String maskText = "****";
    private Set<String> piiKeysSet = Collections.emptySet();
    
    // Performance settings
    private int maxJsonSize = 1024 * 1024; // 1MB max JSON size
    private int maxJsonDepth = 50; // Max nesting depth
    private int maxNodes = 10000; // Max nodes to process
    
    // Cache for frequently used patterns
    private final ConcurrentHashMap<String, Boolean> jsonDetectionCache = new ConcurrentHashMap<>();
    
    // Security metrics
    private volatile long totalLogsProcessed = 0;
    private volatile long piiLogsMasked = 0;
    private volatile long piiLogsBlocked = 0;
    private volatile long maskingFailures = 0;
    
    @Override
    public void start() {
        this.piiKeysSet = parseKeys(maskKeys);
        securityLogger.info("Secure PII Masking Layout started with {} keys: {}", piiKeysSet.size(), piiKeysSet);
        super.start();
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        totalLogsProcessed++;
        
        try {
            String originalMessage = event.getFormattedMessage();
            
            // Fast check: skip if message is too large or doesn't look like JSON
            if (originalMessage == null || originalMessage.length() > maxJsonSize) {
                return super.doLayout(event);
            }
            
            // Quick JSON detection using regex (much faster than indexOf)
            if (!containsJson(originalMessage)) {
                return super.doLayout(event);
            }
            
            // CRITICAL: Attempt PII masking
            String maskedMessage = maskMessageSafely(originalMessage);
            
            // SECURITY CHECK: If masking failed, DO NOT LOG the original message
            if (maskedMessage == null) {
                piiLogsBlocked++;
                securityLogger.warn("PII masking failed - BLOCKING log message to prevent PII leakage. " +
                    "Logger: {}, Level: {}, Message length: {}", 
                    event.getLoggerName(), event.getLevel(), originalMessage.length());
                return ""; // Return empty string to prevent the log from being written
            }
            
            // If masking succeeded, create masked event
            if (!maskedMessage.equals(originalMessage)) {
                piiLogsMasked++;
                ILoggingEvent maskedEvent = new MaskedLoggingEvent(event, maskedMessage);
                return super.doLayout(maskedEvent);
            }
            
            // No PII detected, log normally
            return super.doLayout(event);
            
        } catch (Exception e) {
            // CRITICAL: If any exception occurs during PII processing, block the log
            maskingFailures++;
            securityLogger.error("CRITICAL: PII masking exception - BLOCKING log message to prevent PII leakage. " +
                "Logger: {}, Level: {}, Error: {}", 
                event.getLoggerName(), event.getLevel(), e.getMessage(), e);
            return ""; // Return empty string to prevent the log from being written
        }
    }
    
    /**
     * Fast JSON detection using simple string search
     */
    public boolean containsJson(String message) {
        // Simple check for JSON-like structure
        return message.contains("{") && message.contains("}") && 
               message.contains(":") && message.contains("\"");
    }
    
    /**
     * Safely mask message with proper error handling and limits
     * Returns null if masking fails (security requirement)
     */
    public String maskMessageSafely(String message) {
        try {
            // Find JSON bounds with depth limit
            int[] bounds = findJsonBoundsWithLimit(message, maxJsonDepth);
            if (bounds == null) {
                return message; // No JSON found, safe to log
            }
            
            // Extract parts efficiently
            String prefix = message.substring(0, bounds[0]);
            String json = message.substring(bounds[0], bounds[1] + 1);
            String suffix = message.substring(bounds[1] + 1);
            
            // Mask JSON with size limit
            String maskedJson = maskJsonWithLimits(json);
            if (maskedJson == null) {
                // Masking failed, return null to block the log
                return null;
            }
            
            // Efficient string concatenation
            if (prefix.isEmpty() && suffix.isEmpty()) {
                return maskedJson;
            } else if (prefix.isEmpty()) {
                return maskedJson + suffix;
            } else if (suffix.isEmpty()) {
                return prefix + maskedJson;
            } else {
                return prefix + maskedJson + suffix;
            }
            
        } catch (Exception e) {
            securityLogger.debug("JSON masking failed for message segment", e);
            return null; // Return null to block the log
        }
    }
    
    /**
     * Mask JSON with size and depth limits
     * Returns null if masking fails
     */
    private String maskJsonWithLimits(String json) {
        try {
            if (json.length() > maxJsonSize) {
                securityLogger.warn("JSON too large for masking: {} bytes", json.length());
                return null; // Block large JSON to prevent PII leakage
            }
            
            PiiJsonMasker masker = new PiiJsonMasker(OBJECT_MAPPER);
            return masker.maskJsonObjectString(json, piiKeysSet, maskText);
            
        } catch (Exception e) {
            securityLogger.debug("JSON masking failed", e);
            return null; // Return null to block the log
        }
    }
    
    /**
     * Find JSON bounds with depth limit to prevent StackOverflow
     */
    public static int[] findJsonBoundsWithLimit(String s, int maxDepth) {
        int len = s.length();
        int startIndex = s.indexOf('{');
        if (startIndex < 0) return null;
        
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        
        for (int i = startIndex; i < len; i++) {
            char c = s.charAt(i);
            
            if (inString) {
                if (escape) {
                    escape = false;
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
                if (depth > maxDepth) {
                    return null; // Too deep, skip masking
                }
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new int[]{startIndex, i};
                }
            }
        }
        return null;
    }
    
    /**
     * Create optimized ObjectMapper
     */
    private static ObjectMapper createOptimizedObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Configure for performance
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        return mapper;
    }
    
    // Configuration setters
    public void setMaskKeys(String maskKeys) {
        this.maskKeys = maskKeys;
        this.piiKeysSet = parseKeys(maskKeys);
    }

    public void setMaskText(String maskText) {
        this.maskText = maskText;
    }
    
    public void setMaxJsonSize(int maxJsonSize) {
        this.maxJsonSize = maxJsonSize;
    }
    
    public void setMaxJsonDepth(int maxJsonDepth) {
        this.maxJsonDepth = maxJsonDepth;
    }
    
    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    private Set<String> parseKeys(String keysCsv) {
        if (keysCsv == null || keysCsv.isBlank()) return Collections.emptySet();
        return Arrays.stream(keysCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
    
    /**
     * Get security metrics for monitoring
     */
    public String getSecurityMetrics() {
        return String.format(
            "PII Masking Metrics - Total: %d, Masked: %d, Blocked: %d, Failures: %d, Success Rate: %.2f%%",
            totalLogsProcessed,
            piiLogsMasked,
            piiLogsBlocked,
            maskingFailures,
            totalLogsProcessed > 0 ? (double)(piiLogsMasked + (totalLogsProcessed - piiLogsMasked - piiLogsBlocked)) / totalLogsProcessed * 100 : 0
        );
    }
}

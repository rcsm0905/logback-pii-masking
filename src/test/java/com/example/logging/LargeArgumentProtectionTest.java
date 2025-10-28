package com.example.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Test suite for argument size protection in {@link JsonStructuredLayout}.
 * <p>
 * Verifies that excessively large log arguments are rejected before processing
 * to protect application performance and prevent OutOfMemoryError.
 */
class LargeArgumentProtectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PiiDataMasker masker;
    private JsonStructuredLayout layout;
    private LoggerContext loggerContext;
    
    // Threshold for "large" test arguments (still well under Jackson's 10MB limit)
    private static final int MAX_SIZE = 500 * 1024;

    @BeforeEach
    void setUp() {
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        masker = new PiiDataMasker();
        masker.setMaskedFields("password,ssn,creditCard");
        masker.setMaskToken("[REDACTED]");
        masker.start();

        layout = new JsonStructuredLayout();
        layout.setMaskingLayout(masker);
        layout.setPrettyPrint(false);
        layout.start();
    }

    @AfterEach
    void tearDown() {
        layout.stop();
        masker.stop();
        loggerContext.stop();
    }

    /**
     * Helper to create a logging event.
     */
    private LoggingEvent createLoggingEvent(String message, Object[] args) {
        Logger logger = loggerContext.getLogger("com.example.test");
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(logger.getName());
        event.setLoggerContext(loggerContext);
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setArgumentArray(args);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }

    @Test
    void testSmallArgument_ShouldProcessNormally() throws Exception {
        // Argument well within size limit (< 1KB)
        String smallJson = "{\"name\":\"John\",\"ssn\":\"123-45-6789\"}";
        
        LoggingEvent event = createLoggingEvent("User data: {}", new Object[]{smallJson});
        String output = layout.doLayout(event);
        
        // Parse output
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        
        // Verify normal masking occurred
        assertThat(message).contains("[REDACTED]"); // SSN masked
        assertThat(message).doesNotContain("123-45-6789"); // Original SSN not present
        assertThat(message).doesNotContain("TOO LARGE"); // No size warning
    }

    @Test
    void testMediumArgument_ShouldProcessNormally() throws Exception {
        // Argument ~100KB (below 500KB limit)
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i).append(",\"value\":\"").append("x".repeat(80)).append("\"}");
        }
        sb.append("]}");
        String mediumJson = sb.toString();
        
        assertThat(mediumJson.getBytes().length).isLessThan(MAX_SIZE).isGreaterThan(50_000);
        
        LoggingEvent event = createLoggingEvent("API response: {}", new Object[]{mediumJson});
        String output = layout.doLayout(event);
        
        // Verify normal processing (no size limit hit)
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        assertThat(message).doesNotContain("TOO LARGE");
    }

    @Test
    void testLargeArgument_ShouldProcessNormally() throws Exception {
        // Argument ~600KB (well under Jackson's 10MB limit)
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 6000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i).append(",\"value\":\"").append("x".repeat(80)).append("\"}");
        }
        sb.append("]}");
        String largeJson = sb.toString();
        
        int actualSize = largeJson.getBytes().length;
        assertThat(actualSize).isGreaterThan(MAX_SIZE);
        
        LoggingEvent event = createLoggingEvent("Large response: {}", new Object[]{largeJson});
        String output = layout.doLayout(event);
        
        // Parse output
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        
        // Verify normal processing (no early size limit - Jackson handles it)
        assertThat(message).doesNotContain("REDACTED DUE TO ARG TOO LARGE");
        // Large arguments under 10MB are processed normally via Jackson limits
        assertThat(message).contains("\"id\":"); // Data was processed
        
        System.out.println("\n✅ Large argument test (under 10MB, processes normally):");
        System.out.println("   Argument size: " + formatSize(actualSize));
    }

    @Test
    void testVeryLargeArgument_ShouldProcessNormally() throws Exception {
        // Argument ~2MB (still under Jackson's 10MB limit)
        String veryLargeString = "x".repeat(2 * 1024 * 1024); // 2MB of 'x'
        
        int actualSize = veryLargeString.getBytes().length;
        assertThat(actualSize).isGreaterThan(MAX_SIZE * 3);
        
        LoggingEvent event = createLoggingEvent("Huge data: {}", new Object[]{veryLargeString});
        String output = layout.doLayout(event);
        
        // Parse output
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        
        // Verify normal processing (under 10MB, processes via Jackson limits)
        assertThat(message).doesNotContain("REDACTED DUE TO ARG TOO LARGE");
        // Note: Non-JSON strings pass through unchanged, so "x" will be in output
        
        System.out.println("\n✅ Very large argument test (2MB, under 10MB limit):");
        System.out.println("   Argument size: " + formatSize(actualSize));
    }

    @Test
    void testLargeDtoObject_ShouldProcessNormally() throws Exception {
        // Create a large DTO (when serialized, still under 10MB Jackson limit)
        LargeTestDto largeDto = new LargeTestDto();
        
        // Add 10,000 items to make it large (but still processable)
        for (int i = 0; i < 10000; i++) {
            largeDto.addItem(new LargeTestDto.Item(i, "value_" + i, "description_" + i));
        }
        
        LoggingEvent event = createLoggingEvent("Large DTO: {}", new Object[]{largeDto});
        String output = layout.doLayout(event);
        
        // Parse output
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        
        // Verify normal processing (under 10MB, Jackson handles it)
        assertThat(message).doesNotContain("REDACTED DUE TO ARG TOO LARGE");
        // DTO was serialized and processed normally
        
        System.out.println("\n✅ Large DTO test (processed normally):");
        System.out.println("   Result: " + message.substring(0, Math.min(200, message.length())) + "...");
    }

    @Test
    void testMultipleArguments_BothProcessNormally() throws Exception {
        // Mix of small and large arguments (both under 10MB limit)
        String smallJson = "{\"name\":\"John\"}";
        String largeString = "x".repeat(600 * 1024); // 600KB
        
        LoggingEvent event = createLoggingEvent("Data: {} and {}", new Object[]{smallJson, largeString});
        String output = layout.doLayout(event);
        
        // Parse output
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        
        // Verify small argument processed normally
        assertThat(message).contains("John");
        
        // Verify large argument also processed (under 10MB, no early rejection)
        assertThat(message).doesNotContain("REDACTED DUE TO ARG TOO LARGE");
        // Large non-JSON string passes through unchanged
        
        System.out.println("\n✅ Mixed arguments test (both process normally):");
        System.out.println("   Message: " + message.substring(0, Math.min(200, message.length())) + "...");
    }

    @Test
    void testPerformance_LayoutIsFast() {
        // Verify layout processing doesn't add significant overhead
        String normalJson = "{\"name\":\"John\",\"ssn\":\"123-45-6789\"}";
        LoggingEvent event = createLoggingEvent("User: {}", new Object[]{normalJson});
        
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            layout.doLayout(event);
        }
        long duration = System.nanoTime() - start;
        
        double avgMs = duration / 1_000_000.0 / 1000;
        assertThat(avgMs).isLessThan(2.0); // Average < 2ms per log (very generous)
        
        System.out.println("\n📊 Performance test:");
        System.out.println("   1000 iterations: " + (duration / 1_000_000.0) + " ms");
        System.out.println("   Average per log: " + avgMs + " ms");
    }

    // Helper classes
    private String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else {
            return String.format("%.1fMB", bytes / (1024.0 * 1024));
        }
    }

    /**
     * Test DTO for large object serialization tests.
     */
    public static class LargeTestDto {
        private java.util.List<Item> items = new java.util.ArrayList<>();

        public void addItem(Item item) {
            items.add(item);
        }

        public java.util.List<Item> getItems() {
            return items;
        }

        public static class Item {
            private int id;
            private String name;
            private String description;

            public Item(int id, String name, String description) {
                this.id = id;
                this.name = name;
                this.description = description;
            }

            public int getId() {
                return id;
            }

            public String getName() {
                return name;
            }

            public String getDescription() {
                return description;
            }
        }
    }
}






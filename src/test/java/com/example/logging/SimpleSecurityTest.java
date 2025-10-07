package com.example.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simplified security tests for PII masking layouts
 * Tests the core functionality without complex Logback setup
 */
public class SimpleSecurityTest {
    
    private SecurePiiMaskingLayout secureLayout;
    
    @BeforeEach
    void setUp() {
        secureLayout = new SecurePiiMaskingLayout();
        secureLayout.setMaskKeys("NAME,SSN,EMAIL,CREDIT_CARD");
        secureLayout.setMaskText("****");
        secureLayout.setMaxJsonSize(1024);
        secureLayout.setMaxJsonDepth(5);
    }
    
    @Test
    void testJsonDetection() {
        // Test JSON detection
        assertTrue(secureLayout.containsJson("{\"NAME\":\"John Doe\"}"));
        assertFalse(secureLayout.containsJson("This is a simple message"));
        assertFalse(secureLayout.containsJson("No JSON here"));
    }
    
    @Test
    void testJsonBoundsFinding() {
        String validJson = "{\"NAME\":\"John Doe\",\"SSN\":\"123-45-6789\"}";
        int[] bounds = SecurePiiMaskingLayout.findJsonBoundsWithLimit(validJson, 5);
        assertNotNull(bounds, "Should find JSON bounds");
        assertEquals(0, bounds[0], "Start index should be 0");
        assertEquals(validJson.length() - 1, bounds[1], "End index should be last character");
    }
    
    @Test
    void testJsonMasking() {
        String validJson = "{\"NAME\":\"John Doe\",\"SSN\":\"123-45-6789\",\"EMAIL\":\"john@example.com\"}";
        String masked = secureLayout.maskMessageSafely(validJson);
        assertNotNull(masked, "Should return masked JSON");
        assertTrue(masked.contains("\"NAME\":\"****\""), "Should mask NAME field");
        assertTrue(masked.contains("\"SSN\":\"****\""), "Should mask SSN field");
        assertTrue(masked.contains("\"EMAIL\":\"****\""), "Should mask EMAIL field");
        assertFalse(masked.contains("John Doe"), "Should not contain original PII");
        assertFalse(masked.contains("123-45-6789"), "Should not contain original PII");
    }
    
    @Test
    void testNonJsonMessage() {
        String nonJsonMessage = "This is a simple log message without JSON";
        String result = secureLayout.maskMessageSafely(nonJsonMessage);
        assertEquals(nonJsonMessage, result, "Should return original message for non-JSON");
    }
    
    @Test
    void testMalformedJson() {
        String malformedJson = "{\"NAME\":\"John Doe\",\"SSN\":\"123-45-6789\",\"EMAIL\":\"john@example.com\"";
        String result = secureLayout.maskMessageSafely(malformedJson);
        assertEquals(malformedJson, result, "Should return original message for malformed JSON");
    }
    
    @Test
    void testLargeJsonBlocking() {
        // Create a large JSON that exceeds the size limit
        StringBuilder largeJson = new StringBuilder();
        largeJson.append("{\"NAME\":\"John Doe\",\"SSN\":\"123-45-6789\",\"EMAIL\":\"john@example.com\"");
        
        for (int i = 0; i < 1000; i++) {
            largeJson.append(",\"field").append(i).append("\":\"value").append(i).append("\"");
        }
        largeJson.append("}");
        
        String result = secureLayout.maskMessageSafely(largeJson.toString());
        assertNull(result, "Should return null for large JSON to prevent PII leakage");
    }
    
    @Test
    void testSecurityMetrics() {
        // Test that security metrics are tracked
        String validJson = "{\"NAME\":\"John Doe\",\"SSN\":\"123-45-6789\"}";
        String malformedJson = "{\"NAME\":\"John Doe\",\"SSN\":\"123-45-6789\"";
        
        secureLayout.maskMessageSafely(validJson);
        secureLayout.maskMessageSafely(malformedJson);
        secureLayout.maskMessageSafely("Simple message");
        
        String metrics = secureLayout.getSecurityMetrics();
        assertNotNull(metrics, "Security metrics should be available");
        assertTrue(metrics.contains("Total:"), "Metrics should include total count");
        assertTrue(metrics.contains("Masked:"), "Metrics should include masked count");
        assertTrue(metrics.contains("Blocked:"), "Metrics should include blocked count");
    }
    
    private ILoggingEvent createLoggingEvent(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setLoggerName("test.logger");
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }
}

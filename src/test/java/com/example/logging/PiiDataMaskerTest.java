package com.example.logging;

import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.ContextAwareBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PiiDataMasker
 * Tests PII masking at various nesting depths and error scenarios
 */
class PiiDataMaskerTest {

	private PiiDataMasker masker;

	@BeforeEach
	void setUp() {
		masker = new PiiDataMasker();
		masker.setMaskedFields("NAME,ID_NUMBER,CHINESE_COMMERCIAL_CODE,SSN,EMAIL");
		masker.setMaskToken("[REDACTED]");
		masker.setOcrFields("ocrResultDetail");
		masker.setOcrMaskToken("[REDACTED]");
		masker.setMaskBase64(true);
		masker.setMaxMessageSize(1000000);
		
		// Set up Logback context for status messages (prevents warnings)
		Context mockContext = mock(Context.class);
		masker.setContext(mockContext);
		
		masker.start();
	}

	@Test
	void testBasicPiiMasking() {
		String input = "{\"NAME\":\"John Doe\",\"ID_NUMBER\":\"123-45-6789\"}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertFalse(result.contains("John Doe"), "PII 'John Doe' should be masked");
		assertFalse(result.contains("123-45-6789"), "PII '123-45-6789' should be masked");
		assertTrue(result.contains("[REDACTED]"), "Should contain [REDACTED]");
		assertTrue(result.contains("\"NAME\""), "Field name should be preserved");
	}

	@Test
	void testDeepNesting() {
		String input = "{\"level1\":{\"level2\":{\"level3\":{\"level4\":{\"level5\":{\"NAME\":\"Deep Secret\"}}}}}}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertFalse(result.contains("Deep Secret"), "Deep nested PII should be masked");
		assertTrue(result.contains("[REDACTED]"), "Should contain [REDACTED]");
	}

	@Test
	void testOcrFieldMasking() {
		String input = "{\"ocrResultDetail\":{\"MRZ_NAME\":{\"value\":\"Secret Data\"}}}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertFalse(result.contains("Secret Data"), "OCR detail should be masked");
		assertTrue(result.contains("{[REDACTED]}"), "Should mask entire OCR object");
	}

	@Test
	void testBase64ImageMasking() {
		String input = "{\"imageContent\":[\"/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAoHBxISEhUSEhIVFRUVFRUVFRUVFRUVFRUVFxUZGBYVFhUaHysjGh0oHRUWJTUlKC0vMjIyGSI4PTcwPCsxMi8BCgsLDw4PHRERHS8dHSUvLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vLy8vL//AABEIAoAC0AMBIgACEQEDEQH\"]}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertFalse(result.contains("/9j/4AAQSkZJRg"), "Base64 image should be masked");
		assertTrue(result.contains("[REDACTED]"), "Should contain [REDACTED]");
	}

	@Test
	void testMessageTooLarge() {
		String hugeMessage = "{\"data\":\"" + "x".repeat(2_000_000) + "\"}";
		String result = masker.maskSensitiveDataOptimized(hugeMessage);
		
		assertTrue(result.startsWith("[LOG TOO LARGE - REDACTED FOR SAFETY"), 
			"Oversized messages should be redacted");
		assertFalse(result.contains("xxxxxx"), "Original data should not be in result");
	}

	@Test
	void testInvalidJson() {
		String invalidJson = "{invalid json}";
		String result = masker.maskSensitiveDataOptimized(invalidJson);
		
		assertTrue(result.contains("[MASKING ERROR - LOG REDACTED FOR SAFETY]"), 
			"Invalid JSON should return error message");
		assertFalse(result.contains("{invalid json}"), "Original invalid JSON should not be returned");
	}

	@Test
	void testNullAndEmptyMessages() {
		assertNull(masker.maskSensitiveDataOptimized(null), "Null should return null");
		assertEquals("", masker.maskSensitiveDataOptimized(""), "Empty should return empty");
	}

	@Test
	void testNestedJsonString() {
		String input = "{\"message\":\"Response: {\\\"NAME\\\":\\\"John Doe\\\"}\"}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertFalse(result.contains("John Doe"), "PII in nested JSON string should be masked");
		assertTrue(result.contains("[REDACTED]"), "Should contain [REDACTED]");
	}

	@Test
	void testMultipleFields() {
		String input = "{\"NAME\":\"John\",\"age\":30,\"ID_NUMBER\":\"123\",\"SSN\":\"456\"}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertFalse(result.contains("John"), "NAME should be masked");
		assertFalse(result.contains("\"123\""), "ID_NUMBER should be masked");
		assertFalse(result.contains("\"456\""), "SSN should be masked");
		assertTrue(result.contains("\"age\":30"), "Non-PII field should not be masked");
	}

	@Test
	void testArraysWithPII() {
		String input = "{\"users\":[{\"NAME\":\"Alice\"},{\"NAME\":\"Bob\"}]}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertFalse(result.contains("Alice"), "PII in array should be masked");
		assertFalse(result.contains("Bob"), "PII in array should be masked");
	}

	@Test
	void testFieldNamePreservation() {
		String input = "{\"NAME\":\"Secret\",\"ID_NUMBER\":\"123\"}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		assertTrue(result.contains("\"NAME\""), "Field name NAME should be preserved");
		assertTrue(result.contains("\"ID_NUMBER\""), "Field name ID_NUMBER should be preserved");
		assertFalse(result.contains("Secret"), "Value should be masked");
		assertFalse(result.contains("\"123\""), "Value should be masked");
	}
	
	@Test
	void testRecursionDepthLimit() {
		// Create deeply nested JSON string structure to test recursion limit
		StringBuilder nestedJson = new StringBuilder("{\"message\":\"");
		for (int i = 0; i < 15; i++) {
			nestedJson.append("{\\\"level").append(i).append("\\\":\\\"");
		}
		nestedJson.append("data");
		for (int i = 0; i < 15; i++) {
			nestedJson.append("\\\"}");
		}
		nestedJson.append("\"}");
		
		String result = masker.maskSensitiveDataOptimized(nestedJson.toString());
		
		// Should handle gracefully without StackOverflow
		assertNotNull(result, "Should not throw StackOverflowError");
		assertFalse(result.contains("StackOverflow"), "Should not contain StackOverflow error");
	}
	
	@Test
	void testInitializationValidation() {
		PiiDataMasker invalidMasker = new PiiDataMasker();
		// Don't configure any masking
		invalidMasker.setMaskedFields("");
		invalidMasker.setOcrFields("");
		invalidMasker.setMaskBase64(false);
		invalidMasker.setMaxMessageSize(1000000);
		
		assertThrows(IllegalStateException.class, () -> invalidMasker.start(),
			"Should throw exception if no masking configured");
	}
	
	@Test
	void testMaxMessageSizeValidation() {
		PiiDataMasker invalidMasker = new PiiDataMasker();
		invalidMasker.setMaskedFields("NAME");
		invalidMasker.setMaxMessageSize(0); // Invalid!
		
		assertThrows(IllegalStateException.class, () -> invalidMasker.start(),
			"Should throw exception if maxMessageSize <= 0");
	}
}


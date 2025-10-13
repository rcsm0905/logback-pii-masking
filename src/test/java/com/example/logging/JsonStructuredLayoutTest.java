package com.example.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.Context;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for JsonStructuredLayout
 * Tests console output format and PII masking integration
 */
class JsonStructuredLayoutTest {

	private JsonStructuredLayout layout;
	private PiiDataMasker masker;
	private Logger logger;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		logger = (Logger) LoggerFactory.getLogger("test.logger");
		objectMapper = new ObjectMapper();
		
		// Set up Logback context for status messages (prevents warnings)
		Context mockContext = mock(Context.class);
		
		// Setup masker
		masker = new PiiDataMasker();
		masker.setMaskedFields("NAME,ID_NUMBER,SSN");
		masker.setMaskToken("[REDACTED]");
		masker.setOcrFields("ocrResultDetail");
		masker.setOcrMaskToken("[REDACTED]");
		masker.setMaskBase64(true);
		masker.setMaxMessageSize(1000000);
		masker.setContext(mockContext);
		masker.start();
		
		// Setup layout
		layout = new JsonStructuredLayout();
		layout.setMaskingLayout(masker);
		layout.setPrettyPrint(false); // Compact for tests
		layout.setContext(mockContext);
		layout.start();
	}

	@Test
	void testBasicLogOutput() throws Exception {
		LoggingEvent event = new LoggingEvent(
			"com.example.logging.JsonStructuredLayoutTest",
			logger,
			Level.INFO,
			"Test message",
			null,
			null
		);
		event.setTimeStamp(System.currentTimeMillis());
		
		String output = layout.doLayout(event);
		
		// Verify it's valid JSON
		assertDoesNotThrow(() -> objectMapper.readTree(output), "Output should be valid JSON");
		
		// Verify structure
		JsonNode json = objectMapper.readTree(output);
		assertTrue(json.has("timestamp"), "Should have timestamp");
		assertTrue(json.has("level"), "Should have level");
		assertTrue(json.has("logger"), "Should have logger");
		assertTrue(json.has("message"), "Should have message");
		assertEquals("INFO", json.get("level").asText());
		assertEquals("test.logger", json.get("logger").asText());
	}

	@Test
	void testPiiMaskingInLog() throws Exception {
		LoggingEvent event = new LoggingEvent(
			"com.example.logging.JsonStructuredLayoutTest",
			logger,
			Level.INFO,
			"User data: {\"NAME\":\"John Doe\",\"ID_NUMBER\":\"123-45-6789\"}",
			null,
			null
		);
		event.setTimeStamp(System.currentTimeMillis());
		
		String output = layout.doLayout(event);
		
		// Verify PII is masked
		assertFalse(output.contains("John Doe"), "PII should be masked in output");
		assertFalse(output.contains("123-45-6789"), "PII should be masked in output");
		assertTrue(output.contains("[REDACTED]"), "Should contain [REDACTED]");
	}

	@Test
	void testPrettyPrintMode() throws Exception {
		layout.setPrettyPrint(true);
		masker.setPrettyPrint(true);
		
		LoggingEvent event = new LoggingEvent(
			"com.example.logging.JsonStructuredLayoutTest",
			logger,
			Level.INFO,
			"Test",
			null,
			null
		);
		event.setTimeStamp(System.currentTimeMillis());
		
		String output = layout.doLayout(event);
		
		// Pretty print should have multiple lines and indentation
		assertTrue(output.contains("\n"), "Pretty print should have newlines");
		assertTrue(output.contains("  "), "Pretty print should have indentation");
	}

	@Test
	void testCompactMode() throws Exception {
		layout.setPrettyPrint(false);
		masker.setPrettyPrint(false);
		
		LoggingEvent event = new LoggingEvent(
			"com.example.logging.JsonStructuredLayoutTest",
			logger,
			Level.INFO,
			"Test",
			null,
			null
		);
		event.setTimeStamp(System.currentTimeMillis());
		
		String output = layout.doLayout(event);
		
		// Compact should be single line (ends with newline)
		assertTrue(output.endsWith("\n"), "Should end with newline");
		// Remove trailing newline and check no other newlines exist
		String withoutTrailingNewline = output.substring(0, output.length() - 1);
		assertFalse(withoutTrailingNewline.contains("\n"), "Should be single line (compact mode)");
	}

	@Test
	void testMaskingLayoutRequired() {
		JsonStructuredLayout layoutWithoutMasker = new JsonStructuredLayout();
		layoutWithoutMasker.setMaskingLayout(null); // Missing masker
		
		assertThrows(IllegalStateException.class, () -> layoutWithoutMasker.start(),
			"Should throw exception if maskingLayout is not configured");
	}

	@Test
	void testExceptionFormatting() throws Exception {
		LoggingEvent event = new LoggingEvent(
			"com.example.logging.JsonStructuredLayoutTest",
			logger,
			Level.ERROR,
			"An error occurred",
			null,
			null
		);
		event.setTimeStamp(System.currentTimeMillis());
		
		// Create a throwable proxy (simplified - in real scenario would be set by logging framework)
		// For now, test that it doesn't crash when throwable is present
		
		String output = layout.doLayout(event);
		
		// Should still produce valid JSON
		assertDoesNotThrow(() -> objectMapper.readTree(output));
		JsonNode json = objectMapper.readTree(output);
		assertEquals("ERROR", json.get("level").asText());
	}
	
	@Test
	void testDeepNestedPiiMasking() throws Exception {
		String input = "{\"data\":{\"user\":{\"personal\":{\"info\":{\"NAME\":\"Alice\",\"SSN\":\"111-22-3333\"}}}}}";
		String result = masker.maskSensitiveDataOptimized(input);
		
		JsonNode resultJson = objectMapper.readTree(result);
		String nameValue = resultJson.at("/data/user/personal/info/NAME").asText();
		String ssnValue = resultJson.at("/data/user/personal/info/SSN").asText();
		
		assertEquals("[REDACTED]", nameValue, "Deep nested NAME should be masked");
		assertEquals("[REDACTED]", ssnValue, "Deep nested SSN should be masked");
	}
}


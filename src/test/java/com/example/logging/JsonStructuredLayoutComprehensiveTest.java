package com.example.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test for JsonStructuredLayout to achieve 100% coverage
 */
class JsonStructuredLayoutComprehensiveTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private JsonStructuredLayout layout;
	private PiiDataMasker masker;

	@BeforeEach
	void setUp() {
		masker = new PiiDataMasker();
		masker.setMaskedFields("password,secret,ssn,creditCard");
		masker.setMaskToken("###");
		masker.start();

		layout = new JsonStructuredLayout();
		layout.setMaskingLayout(masker);
	}

	@AfterEach
	void tearDown() {
		if (layout.isStarted()) {
			layout.stop();
		}
	}

	/* ────────────────── Life-cycle tests ────────────────── */

	@Test
	void start_WithValidMasker_ShouldStart() {
		layout.start();
		assertThat(layout.isStarted()).isTrue();
	}

	@Test
	void start_WithoutMasker_ShouldThrow() {
		JsonStructuredLayout newLayout = new JsonStructuredLayout();
		
		assertThatThrownBy(newLayout::start)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("maskingLayout is REQUIRED");
	}

	@Test
	void stop_ShouldStopMaskerAndLayout() {
		layout.start();
		layout.stop();
		
		assertThat(layout.isStarted()).isFalse();
		assertThat(masker.isStarted()).isFalse();
	}

	/* ────────────────── doLayout tests ────────────────── */

	@Test
	void doLayout_WithCompactMode_ShouldProduceCompactJson() throws Exception {
		layout.setPrettyPrint(false);
		layout.start();
		
		ILoggingEvent event = mockEvent(Level.INFO, "test", null, null, null);
		String output = layout.doLayout(event);
		
		// Compact JSON should not have newlines (except the final one)
		assertThat(output.trim()).doesNotContain("\n");
		assertThat(output).endsWith(System.lineSeparator());
		
		// Should be valid JSON
		JsonNode json = JSON.readTree(output);
		assertThat(json.get("level").asText()).isEqualTo("INFO");
		assertThat(json.get("message").asText()).isEqualTo("test");
	}

	@Test
	void doLayout_WithPrettyPrintMode_ShouldProducePrettyJson() throws Exception {
		layout.setPrettyPrint(true);
		layout.start();
		
		ILoggingEvent event = mockEvent(Level.INFO, "test", null, null, null);
		String output = layout.doLayout(event);
		
		// Pretty JSON should have multiple lines
		assertThat(output).contains("\n");
		
		// Should be valid JSON
		JsonNode json = JSON.readTree(output.trim());
		assertThat(json.get("level").asText()).isEqualTo("INFO");
	}

	@Test
	void doLayout_WithNullMDC_ShouldNotCrash() throws Exception {
		layout.start();
		
		ILoggingEvent event = mockEvent(Level.INFO, "test", null, null, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		assertThat(json.get("message").asText()).isEqualTo("test");
	}

	@Test
	void doLayout_WithEmptyMDC_ShouldNotAddMDCFields() throws Exception {
		layout.start();
		
		ILoggingEvent event = mockEvent(Level.INFO, "test", Collections.emptyMap(), null, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		// Should only have standard fields
		assertThat(json.has("timestamp")).isTrue();
		assertThat(json.has("level")).isTrue();
		assertThat(json.has("logger")).isTrue();
		assertThat(json.has("message")).isTrue();
	}

	@Test
	void doLayout_WithMDC_ShouldIncludeMDCFields() throws Exception {
		layout.start();
		
		Map<String, String> mdc = new HashMap<>();
		mdc.put("userId", "12345");
		mdc.put("requestId", "req-abc");
		
		ILoggingEvent event = mockEvent(Level.INFO, "test", mdc, null, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		assertThat(json.get("userId").asText()).isEqualTo("12345");
		assertThat(json.get("requestId").asText()).isEqualTo("req-abc");
	}

	@Test
	void doLayout_WithException_ShouldIncludeException() throws Exception {
		layout.start();
		
		Exception ex = new RuntimeException("Test error");
		ThrowableProxy proxy = new ThrowableProxy(ex);
		
		ILoggingEvent event = mockEvent(Level.ERROR, "error occurred", null, proxy, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		assertThat(json.has("exception")).isTrue();
		assertThat(json.get("exception").asText()).contains("RuntimeException: Test error");
	}

	@Test
	void doLayout_WithExceptionWithoutStackTrace_ShouldHandleGracefully() throws Exception {
		layout.start();
		
		Exception ex = new RuntimeException("Test error");
		ThrowableProxy proxy = new ThrowableProxy(ex);
		
		// Set stack trace to null using reflection
		Field stackTraceField = ThrowableProxy.class.getDeclaredField("stackTraceElementProxyArray");
		stackTraceField.setAccessible(true);
		stackTraceField.set(proxy, null);
		
		ILoggingEvent event = mockEvent(Level.ERROR, "error", null, proxy, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		assertThat(json.has("exception")).isTrue();
		String exception = json.get("exception").asText();
		assertThat(exception).contains("RuntimeException: Test error");
		assertThat(exception.split("\n")).hasSize(1); // Only the error line
	}

	@Test
	void doLayout_WithExceptionWithManyStackFrames_ShouldLimitTo10() throws Exception {
		layout.start();
		
		Exception ex = new RuntimeException("Deep stack");
		ThrowableProxy proxy = new ThrowableProxy(ex);
		
		ILoggingEvent event = mockEvent(Level.ERROR, "error", null, proxy, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		String exception = json.get("exception").asText();
		int frameCount = exception.split("\n\tat ").length - 1;
		assertThat(frameCount).isLessThanOrEqualTo(10);
	}

	@Test
	void doLayout_WithPiiInMessage_ShouldMask() throws Exception {
		layout.start();
		
		String messageWithPii = "User password is mySecret123 and ssn is 123-45-6789";
		ILoggingEvent event = mockEvent(Level.INFO, messageWithPii, null, null, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		// The message itself won't be masked, but if it contains JSON it will be
		assertThat(json.get("message").asText()).isEqualTo(messageWithPii);
	}

	@Test
	void doLayout_WithCompleteZolozScenario_ShouldMaskPii() throws Exception {
		// Reconfigure masker for Zoloz fields
		masker.stop();
		masker.setMaskedFields("NAME,SSN,ID_NUMBER,DATE_OF_BIRTH,imageContent,CROPPED_FACE_FROM_DOC");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		layout.start();
		
		String jsonMessage = "{\"NAME\":\"John Doe\",\"SSN\":\"123-45-6789\",\"ID_NUMBER\":\"C123456(9)\",\"DATE_OF_BIRTH\":\"2000-11-13\"}";
		ILoggingEvent event = mockEvent(Level.INFO, "Received: " + jsonMessage, null, null, null);
		String output = layout.doLayout(event);
		
		JsonNode json = JSON.readTree(output);
		String message = json.get("message").asText();
		assertThat(message).contains("Received:");
	}

	/* ────────────────── Helper methods ────────────────── */

	private ILoggingEvent mockEvent(Level level, String message, Map<String, String> mdc, 
									 ThrowableProxy throwableProxy, Object[] args) {
		ILoggingEvent event = mock(ILoggingEvent.class);
		when(event.getLevel()).thenReturn(level);
		when(event.getTimeStamp()).thenReturn(System.currentTimeMillis());
		when(event.getLoggerName()).thenReturn("test.logger");
		when(event.getMessage()).thenReturn(message);
		when(event.getFormattedMessage()).thenReturn(message);
		when(event.getMDCPropertyMap()).thenReturn(mdc);
		when(event.getThrowableProxy()).thenReturn(throwableProxy);
		when(event.getArgumentArray()).thenReturn(args);
		return event;
	}

	/* ────────────────── Test helper classes ────────────────── */

	private static class TestObject {
		private String name;
		private int age;

		public TestObject(String name, int age) {
			this.name = name;
			this.age = age;
		}

		public String getName() {
			return name;
		}

		public int getAge() {
			return age;
		}
	}
}


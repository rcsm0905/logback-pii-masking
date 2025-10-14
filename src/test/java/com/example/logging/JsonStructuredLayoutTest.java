package com.example.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import java.lang.reflect.Field;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Completes coverage for every remaining branch/method in
 * {@link JsonStructuredLayout}.
 */
class JsonStructuredLayoutExhaustiveTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private JsonStructuredLayout layout;

	/* ────────────────────  test bootstrapping  ──────────────────── */

	@BeforeEach
	void givenStartedLayout_WhenBootstrapping_ThenReadyForUse() {
		// full-featured masker
		PiiDataMasker masker = new PiiDataMasker();
		masker.setMaskedFields("secret");
		masker.setMaskToken("###");
		masker.start();

		layout = new JsonStructuredLayout();
		layout.setMaskingLayout(masker);
		layout.start();
	}

	@AfterEach
	void whenTearDown_ThenLayoutIsStopped() {
		layout.stop();
	}

	/* ─────────────── doLayout() remaining branches ─────────────── */

	@Test
	void nullMdcAndNoThrowable_WhenDoLayout_ThenJsonProduced() throws Exception {
		// Mock the logging event to avoid NullPointerException with MDC
		ILoggingEvent ev = mock(ILoggingEvent.class);
		when(ev.getLevel()).thenReturn(Level.INFO);
		when(ev.getTimeStamp()).thenReturn(System.currentTimeMillis());
		when(ev.getLoggerName()).thenReturn("JUnit");
		when(ev.getMessage()).thenReturn("plain");
		when(ev.getFormattedMessage()).thenReturn("plain");
		when(ev.getMDCPropertyMap()).thenReturn(null);  // ← null-MDC path
		when(ev.getArgumentArray()).thenReturn(null);
		when(ev.getThrowableProxy()).thenReturn(null);

		String out = layout.doLayout(ev);

		// ① must not be the fallback line
		assertThat(out).doesNotContain("[LAYOUT ERROR]");

		// ② locate the JSON start safely and parse
		int brace = out.indexOf('{');
		assertThat(brace).isNotNegative();

		JsonNode root = JSON.readTree(out.substring(brace));
		assertThat(root.get("logger").asText()).isEqualTo("JUnit");
		assertThat(root.has("exception")).isFalse();
	}

	/* ─────────────── formatMessage() variations ─────────────── */

	@Test
	void simpleStringArg_WhenFormatMessage_ThenLeftUntouched() throws Exception {
		// Mock the logging event with proper message formatting
		ILoggingEvent ev = mock(ILoggingEvent.class);
		when(ev.getMessage()).thenReturn("hello {}");
		when(ev.getFormattedMessage()).thenReturn("hello Bob");
		when(ev.getArgumentArray()).thenReturn(new Object[] { "Bob" });
		when(ev.getMDCPropertyMap()).thenReturn(Collections.emptyMap());
		when(ev.getThrowableProxy()).thenReturn(null);

		Method m = JsonStructuredLayout.class.getDeclaredMethod(
				"formatMessage", ILoggingEvent.class);
		m.setAccessible(true);

		assertThat(m.invoke(layout, ev)).isEqualTo("hello Bob");
	}

	@Test
	void noArguments_WhenFormatMessage_ThenMessageReturnedAsIs() throws Exception {
		ILoggingEvent ev = event("stand-alone message", null);

		Method m = JsonStructuredLayout.class.getDeclaredMethod(
				"formatMessage", ILoggingEvent.class);
		m.setAccessible(true);

		assertThat(m.invoke(layout, ev)).isEqualTo("stand-alone message");
	}

	/* ─────────────── isComplexObject() negative cases ─────────────── */

	@Test
	void primitiveStringAndEnum_WhenIsComplexObject_ThenReturnFalse() throws Exception {
		Method m = JsonStructuredLayout.class.getDeclaredMethod(
				"isComplexObject", Object.class);
		m.setAccessible(true);

		assertThat(m.invoke(layout, 7)).isEqualTo(false);
		assertThat(m.invoke(layout, "txt")).isEqualTo(false);
		assertThat(m.invoke(layout, SampleEnum.A)).isEqualTo(false);
	}

	private enum SampleEnum { A }

	/* ─────────────── formatException() zero-frames branch ─────────────── */

	@Test
	void throwableProxyWithoutFrames_WhenFormatException_ThenSingleLine() throws Exception {
		// Create a real ThrowableProxy instead of mocking to avoid Java 25 compatibility issues
		Exception testException = new RuntimeException("oops");
		ThrowableProxy proxy = new ThrowableProxy(testException);
		
		// Use reflection to set the stack trace to null to simulate zero frames
		Field stackTraceField = ThrowableProxy.class.getDeclaredField("stackTraceElementProxyArray");
		stackTraceField.setAccessible(true);
		stackTraceField.set(proxy, null);

		// Mock the logging event to avoid NullPointerException with MDC
		ILoggingEvent ev = mock(ILoggingEvent.class);
		when(ev.getThrowableProxy()).thenReturn(proxy);

		Method fe = JsonStructuredLayout.class.getDeclaredMethod(
				"formatException", ILoggingEvent.class);
		fe.setAccessible(true);

		String out = (String) fe.invoke(layout, ev);
		assertThat(out.split("\n")).hasSize(1);     // only "RuntimeException: oops"
	}

	/* ─────────────── formatFallback() helper ─────────────── */

	@Test
	void throwableInsideLayout_WhenFormatFallback_ThenMinimalOneLiner() throws Exception {
		ILoggingEvent ev = event("irrelevant", null);
		Exception boom = new IllegalStateException("fail");

		Method ff = JsonStructuredLayout.class.getDeclaredMethod(
				"formatFallback", ILoggingEvent.class, Exception.class);
		ff.setAccessible(true);

		String out = (String) ff.invoke(layout, ev, boom);
		assertThat(out).contains("[LAYOUT ERROR] IllegalStateException: fail");
	}

	/* ─────────────── small helper ─────────────── */

	private static ILoggingEvent event(String msg, Object[] args) {
		// Mock the logging event to avoid NullPointerException with MDC
		ILoggingEvent ev = mock(ILoggingEvent.class);
		when(ev.getLevel()).thenReturn(Level.INFO);
		when(ev.getTimeStamp()).thenReturn(System.currentTimeMillis());
		when(ev.getLoggerName()).thenReturn("test");
		when(ev.getMessage()).thenReturn(msg);
		when(ev.getFormattedMessage()).thenReturn(msg);
		when(ev.getArgumentArray()).thenReturn(args);
		when(ev.getMDCPropertyMap()).thenReturn(Collections.emptyMap());
		when(ev.getThrowableProxy()).thenReturn(null);
		return ev;
	}
}
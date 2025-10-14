package com.example.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.Setter;

/**
 * • Builds one {@code ObjectNode} per log event, adds timestamp/level/logger/
 *   MDC/message/exception.
 * • Calls {@link PiiDataMasker#maskJsonTree} to redact PII in-place.
 * • Serializes once (pretty in “local” profile, compact otherwise) and returns
 *   the line to Logback.
 * <p>
 * Why a custom layout?
 * - No extra dependency; direct call to the tree-walker masker.
 * - One Jackson pass  (ObjectNode → mask → write) instead of
 *   Map → string → parse → mask → string.
 * <p>
 * Extra safety helpers
 * --------------------
 * • {@code formatMessage}  – detects complex arguments, replaces their
 *   {@code toString()} with compact JSON so nested fields can still be masked.
 *   Cost is paid only when such an argument exists.
 * • {@code isComplexObject} – cheap guard to skip primitives/Strings/enums.
 * <p>
 * Current masking limitation
 * --------------------------
 * The masker operates on valid JSON.  If a user logs a complex object without
 * the helper (e.g. via plain {@code toString()}) its internal fields are *not*
 * parsed and therefore cannot be masked.
 * <p>
 * Config knobs (see logback-spring.xml)
 * -------------------------------------
 * • {@code <prettyPrint>}      – pretty vs. compact output.
 * • {@code <maskedFields>} etc – passed to the nested {@code PiiDataMasker}.
 * <p>
 * Thread-safety
 * -------------
 * Static mappers and formatter are immutable; the layout holds no mutable
 * state between calls.
 */
@Setter
public class JsonStructuredLayout extends LayoutBase<ILoggingEvent> {

	private static final DateTimeFormatter ISO_FORMATTER =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
					.withZone(ZoneId.systemDefault());

	private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper()
			.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

	private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
			.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
			.configure(SerializationFeature.INDENT_OUTPUT, true);

	// Nested PII masking component
	private PiiDataMasker maskingLayout;

	/**
	 * Pretty print for local dev (configurable via logback-spring.xml)
	 * If true, use {@link #PRETTY_MAPPER}; otherwise use {@link #COMPACT_MAPPER}.
	 * */
	private boolean prettyPrint;

	/**
	 * Convert one {@link ILoggingEvent} to a JSON line.
	 * <p>
	 * 1. Build an {@code ObjectNode} containing the canonical fields we want
	 *    (`timestamp`, `level`, `logger`, `message`, MDC, optional `exception`).
	 * 2. Invoke {@code maskingLayout.maskJsonTree(root)} which redacts any
	 *    configured fields **in the same tree instance**.
	 * 3. Serialize the final tree once and append a platform line-separator.
	 * <p>
	 * If *anything* inside the try-block throws, we fall back to
	 * {@link #formatFallback} to ensure no PII is leaked and the application
	 * keeps running.
	 */
	@Override
	public String doLayout(ILoggingEvent ev) {
		try {
			/* 1 ─ Build the JSON tree */
			ObjectMapper mapper = prettyPrint ? PRETTY_MAPPER : COMPACT_MAPPER;
			ObjectNode root = mapper.createObjectNode();
			root.put("timestamp", ISO_FORMATTER.format(Instant.ofEpochMilli(ev.getTimeStamp())));
			root.put("level", ev.getLevel().toString());
			root.put("logger", ev.getLoggerName());
			// MDC context
			Map<String, String> mdc = ev.getMDCPropertyMap();
			if (mdc != null && !mdc.isEmpty()) {
				mdc.forEach(root::put);
			}
			root.set("message", TextNode.valueOf(formatMessage(ev)));
			if (ev.getThrowableProxy() != null) {
				root.set("exception", TextNode.valueOf(formatException(ev)));
			}
			/* 2 ─ Mask PII in-place */
			maskingLayout.maskJsonTree(root);
			/* 3 ─ Serialize once and return */
			return mapper.writeValueAsString(root) + System.lineSeparator();
		} catch (Exception ex) {
			return formatFallback(ev, ex);
		}
	}

	/**
	 * Replace any “complex” argument objects with JSON so that the final
	 * {@code message} field is readable *and* maskable.
	 * <p>
	 * Example                                                <br>
	 * {@code log.info("response={}", respObj)}               <br>
	 * – default SLF4J would output {@code response=com.demo.Resp@3c4a} <br>
	 * – this method turns it into {@code response={"code":200,"body":"ok"}}.
	 * <p>
	 * The extra cost (~20 µs) is paid **only** when an argument is identified as
	 * complex by {@link #isComplexObject(Object)}.
	 */
	private String formatMessage(ILoggingEvent event) {
		String formattedMessage = event.getFormattedMessage();

		// If arguments are present and contain objects, try to serialize them
		Object[] args = event.getArgumentArray();
		if (args != null) {
			for (Object arg : args) {
				if (isComplexObject(arg)) {
					try {
						// Replace object toString with JSON serialization (always compact in message)
						String objectJson = COMPACT_MAPPER.writeValueAsString(arg);
						formattedMessage = formattedMessage.replace(arg.toString(), objectJson);
					} catch (Exception e) {
						// Failed to serialize object argument to JSON - keep original (no need to log this)
					}
				}
			}
		}

		return formattedMessage;
	}

	/**
	 * Return {@code true} if the argument looks “complex” enough to justify JSON
	 * serialization.  Primitive wrappers, Strings, enums and Numbers are treated
	 * as *simple* and left as-is.
	 */
	private boolean isComplexObject(Object obj) {
		if (obj == null) return false;

		Class<?> c = obj.getClass();

		return !c.isPrimitive()                 // primitives
				&& !c.equals(String.class)          // strings
				&& !Number.class.isAssignableFrom(c) // all numeric wrappers
				&& !c.equals(Boolean.class)         // Boolean wrapper
				&& !c.isEnum();                     // enums
	}

	/**
	 * Condense the throwable into “Class: message” plus the first 10 stack frames
	 * to keep the log line short.
	 */
	private String formatException(ILoggingEvent event) {
		var th = event.getThrowableProxy();
		StringBuilder sb = new StringBuilder()
				.append(th.getClassName()).append(": ").append(th.getMessage());

		var frames = th.getStackTraceElementProxyArray();
		if (frames != null && frames.length > 0) {
			sb.append("\n");
			for (int i = 0, limit = Math.min(10, frames.length); i < limit; i++) {
				sb.append("\tat ").append(frames[i]).append("\n");
			}
		}
		return sb.toString();
	}

	/**
	 * Executed when *this* layout throws.  We log a minimal one-liner that
	 * contains no PII but still indicates the error.
	 */
	private String formatFallback(ILoggingEvent event, Exception ex) {
		return String.format("%s [%s] %s - [LAYOUT ERROR] %s: %s%n",
				ISO_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())),
				event.getLevel(),
				event.getLoggerName(),
				ex.getClass().getSimpleName(),
				ex.getMessage());
	}

	@Override
	public void start() {
		if (maskingLayout == null) {
			addError("CRITICAL – PiiDataMasker missing, refusing to start");
			throw new IllegalStateException("maskingLayout is REQUIRED");
		}
		maskingLayout.start();
		super.start();
	}

	@Override
	public void stop() {
		maskingLayout.stop();
		super.stop();
	}
}


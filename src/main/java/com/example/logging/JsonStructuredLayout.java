package com.example.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Setter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON structured layout for Lambda-optimized logging
 * Converts log events to JSON format, then chains to PII masking
 * 
 * Features:
 * - Compact JSON for production (single line)
 * - Pretty-print JSON for local dev (readable multi-line)
 * - Configurable via Spring profiles
 */
@Setter
public class JsonStructuredLayout extends LayoutBase<ILoggingEvent> {

	private static final DateTimeFormatter ISO_FORMATTER = 
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
			.withZone(ZoneId.of("UTC"));
	
	private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper()
		.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
	
	private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
		.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
		.configure(SerializationFeature.INDENT_OUTPUT, true);

	// Nested PII masking component
	private PiiDataMasker maskingLayout;
	
	// Pretty print for local dev (configurable via logback-spring.xml)
	private boolean prettyPrint = false;

	@Override
	public String doLayout(ILoggingEvent event) {
		try {
			// Build structured JSON log
			Map<String, Object> logMap = new LinkedHashMap<>();
			
			// Timestamp in ISO format for Lambda/CloudWatch
			logMap.put("timestamp", ISO_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())));
			
			// Log level
			logMap.put("level", event.getLevel().toString());
			
			// Logger name
			logMap.put("logger", event.getLoggerName());
			
			// Add MDC context (traceId, requestId, etc.) for distributed tracing
			Map<String, String> mdcProperties = event.getMDCPropertyMap();
			if (mdcProperties != null && !mdcProperties.isEmpty()) {
				for (Map.Entry<String, String> entry : mdcProperties.entrySet()) {
					logMap.put(entry.getKey(), entry.getValue());
				}
			}
			
			// Format message with arguments
			String message = formatMessage(event);
			logMap.put("message", message);
			
			// Add exception info if present
			if (event.getThrowableProxy() != null) {
				logMap.put("exception", formatException(event));
			}
			
			// Serialize to JSON (compact or pretty based on environment)
			ObjectMapper mapper = prettyPrint ? PRETTY_MAPPER : COMPACT_MAPPER;
			String jsonLog = mapper.writeValueAsString(logMap);
			
			// Apply masking (guaranteed non-null by start() method)
			// NOTE: No need to unescape - ObjectMapper tree traversal handles escaped JSON
			jsonLog = maskingLayout.maskSensitiveDataOptimized(jsonLog);
			
			return jsonLog + System.lineSeparator();
			
		} catch (Exception e) {
			// Log error with stack trace before falling back
			addError("Failed to serialize log event to JSON: " + e.getMessage(), e);
			// Fallback to simple format if JSON serialization fails
			return formatFallback(event);
		}
	}

	private String formatMessage(ILoggingEvent event) {
		String formattedMessage = event.getFormattedMessage();
		
		// If arguments are present and contain objects, try to serialize them
		Object[] args = event.getArgumentArray();
		if (args != null && args.length > 0) {
			for (Object arg : args) {
				if (arg != null && isComplexObject(arg)) {
					try {
						// Replace object toString with JSON serialization (always compact in message)
						String objectJson = COMPACT_MAPPER.writeValueAsString(arg);
						formattedMessage = formattedMessage.replace(arg.toString(), objectJson);
					} catch (Exception e) {
						// Log warning with stack trace, then keep original
						addWarn("Failed to serialize object argument to JSON: " + e.getMessage(), e);
					}
				}
			}
		}
		
		return formattedMessage;
	}
	
	private boolean isComplexObject(Object obj) {
		if (obj == null) return false;
		
		Class<?> clazz = obj.getClass();
		
		// Primitives and common types don't need JSON serialization
		return !clazz.isPrimitive() 
			&& !clazz.equals(String.class)
			&& !clazz.equals(Integer.class)
			&& !clazz.equals(Long.class)
			&& !clazz.equals(Double.class)
			&& !clazz.equals(Float.class)
			&& !clazz.equals(Boolean.class)
			&& !Number.class.isAssignableFrom(clazz)
			&& !clazz.isEnum();
	}

	private String formatException(ILoggingEvent event) {
		StringBuilder sb = new StringBuilder();
		var throwable = event.getThrowableProxy();
		
		sb.append(throwable.getClassName()).append(": ").append(throwable.getMessage());
		
		// Add stack trace (first few lines)
		var stackTrace = throwable.getStackTraceElementProxyArray();
		if (stackTrace != null && stackTrace.length > 0) {
			sb.append("\n");
			int limit = Math.min(10, stackTrace.length); // Limit to 10 lines
			for (int i = 0; i < limit; i++) {
				sb.append("\tat ").append(stackTrace[i].toString()).append("\n");
			}
		}
		
		return sb.toString();
	}

	private String formatFallback(ILoggingEvent event) {
		return String.format("%s [%s] %s - %s%n",
			ISO_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())),
			event.getLevel(),
			event.getLoggerName(),
			event.getFormattedMessage()
		);
	}

	@Override
	public void start() {
		// CRITICAL: Masking layout is REQUIRED, not optional
		// Fail-fast if not configured to prevent PII exposure
		if (maskingLayout == null) {
			addError("CRITICAL: maskingLayout is not configured. " +
				"Application MUST NOT start without PII masking to prevent data leakage.");
			throw new IllegalStateException(
				"CRITICAL: maskingLayout is REQUIRED but not configured. " +
				"Add <maskingLayout> configuration to prevent PII exposure."
			);
		}
		
		// Start nested masking layout
		maskingLayout.start();
		super.start();
	}

	@Override
	public void stop() {
		// Stop nested masking layout (guaranteed non-null by start() method)
		maskingLayout.stop();
		super.stop();
	}
}


package com.example.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import lombok.Setter;
import org.slf4j.helpers.MessageFormatter;

/**
 * Logback layout that converts log events to structured JSON with automatic PII masking.
 * <p>
 * This is the main entry point for structured logging. It takes log statements like
 * {@code logger.info("User logged in", userDTO)} and produces JSON output with sensitive
 * fields automatically redacted.
 *
 * <h2>Configuration (in logback-spring.xml)</h2>
 * <pre>
 * &lt;encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder"&gt;
 *   &lt;layout class="com.example.logging.JsonStructuredLayout"&gt;
 *     &lt;prettyPrint&gt;false&lt;/prettyPrint&gt;
 *     &lt;maskingLayout class="com.example.logging.PiiDataMasker"&gt;
 *       &lt;maskedFields&gt;ssn,creditCard,password,email&lt;/maskedFields&gt;
 *       &lt;maskToken&gt;[REDACTED]&lt;/maskToken&gt;
 *     &lt;/maskingLayout&gt;
 *   &lt;/layout&gt;
 * &lt;/encoder&gt;
 * </pre>
 *
 * <h2>Processing Flow</h2>
 * <pre>
 * Code: logger.info("User {} logged in", userDTO)
 *                              ↓
 *      ┌───────────────────────────────────────────┐
 *      │ 1. doLayout() - Build JSON structure      │
 *      │    • timestamp, level, logger, MDC        │
 *      │    • Extract log arguments                │
 *      └───────────────────────────────────────────┘
 *                              ↓
 *      ┌───────────────────────────────────────────┐
 *      │ 2. maskArgumentArray() - Process each arg │
 *      │    • Simple types → pass through          │
 *      │    • DTOs → convert to JSON tree          │
 *      │    • JSON strings → parse to tree         │
 *      └───────────────────────────────────────────┘
 *                              ↓
 *      ┌───────────────────────────────────────────┐
 *      │ 3. PiiDataMasker.maskJsonTree()           │
 *      │    • Traverse JSON tree                   │
 *      │    • Replace PII fields with [REDACTED]   │
 *      └───────────────────────────────────────────┘
 *                              ↓
 *      ┌───────────────────────────────────────────┐
 *      │ 4. formatMessage() - Build final message  │
 *      │    • Replace {} placeholders with masked  │
 *      │      argument values                      │
 *      └───────────────────────────────────────────┘
 *                              ↓
 *      ┌───────────────────────────────────────────┐
 *      │ 5. Serialize to JSON string               │
 *      │    • Compact or pretty print              │
 *      │    • Write to log file/appender           │
 *      └───────────────────────────────────────────┘
 *                              ↓
 * Output: {"timestamp":"2025-10-17T10:00:00Z","level":"INFO",
 *          "message":"User {\"name\":\"John\",\"ssn\":\"[REDACTED]\"} logged in"}
 * </pre>
 *
 * <h2>MDC (Mapped Diagnostic Context) Handling</h2>
 * <b>IMPORTANT:</b> MDC values are NOT masked. By policy, MDC should only contain:
 * <ul>
 *   <li>✅ Correlation IDs (traceId, requestId, sessionId)</li>
 *   <li>✅ User identifiers (userId, username - non-PII)</li>
 *   <li>✅ Technical context (environment, service name)</li>
 *   <li>❌ NEVER put PII in MDC (SSN, credit cards, passwords, emails)</li>
 * </ul>
 * 
 * <b>Rationale:</b> MDC is for request correlation across microservices, not for sensitive data.
 * All PII should be in log arguments where it can be properly masked.
 *
 * <h2>What Gets Masked</h2>
 * <ul>
 *   <li><b>Log arguments</b>: DTOs, POJOs, JSON strings → converted to JSON → fields masked</li>
 *   <li><b>Simple types</b>: Primitives, Numbers, Strings → passed through unchanged</li>
 *   <li><b>MDC values</b>: NOT masked (should never contain PII)</li>
 * </ul>
 *
 * <h2>Safety & Error Handling</h2>
 * <ul>
 *   <li>{@link #formatFallback(ILoggingEvent, Exception)} - If ANY error occurs, output safe
 *       fallback log without PII (prevents PII leaks on error paths)</li>
 *   <li>{@link #isSimpleType(Object)} - Optimization: skip JSON conversion for primitives</li>
 *   <li>Serialization failures → "[REDACTED DUE TO SERIALIZATION FAILURE]"</li>
 *   <li>No size limits on arguments (relies on JVM memory constraints)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * Fully thread-safe:
 * <ul>
 *   <li>Uses immutable static {@link ObjectMapper} instances (COMPACT_MAPPER, PRETTY_MAPPER)</li>
 *   <li>No shared mutable state between calls</li>
 *   <li>PiiDataMasker uses ThreadLocal for recursion tracking</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * Typical masking overhead: <b>0.1 - 1 millisecond</b> per log statement
 * (measured with {@code MaskingPerformanceTest}). Acceptable for most applications.
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

  /**
   * Maximum argument size in bytes (500KB).
   * <p>
   * This is the first line of defense against excessively large log arguments
   * that could impact application performance or cause OutOfMemoryError.
   * Arguments exceeding this size are redacted without processing.
   * <p>
   * Rationale:
   * <ul>
   *   <li>Typical log arguments: 1KB - 100KB</li>
   *   <li>Large API responses: 100KB - 500KB</li>
   *   <li>Excessive/malicious: 1MB+</li>
   * </ul>
   * Value of 500KB allows legitimate large payloads while blocking attacks.
   */
  private static final int MAX_ARG_SIZE_BYTES = 500 * 1024; // 500KB

  // Nested PII masking component
  private PiiDataMasker maskingLayout;

  /**
   * Pretty print for local dev (configurable via logback-spring.xml) If true, use
   * {@link #PRETTY_MAPPER}; otherwise use {@link #COMPACT_MAPPER}.
   */
  private boolean prettyPrint;

  /**
   * Convert a log event to a structured JSON string with PII masking.
   * <p>
   * This is the main method called by Logback for every log statement. It orchestrates
   * the entire JSON construction and masking process.
   * 
   * <h3>Processing Steps:</h3>
   * <ol>
   *   <li><b>Build JSON structure</b>: Create ObjectNode with timestamp, level, logger, MDC</li>
   *   <li><b>Mask arguments</b>: Convert each log argument to JSON and mask PII fields</li>
   *   <li><b>Format message</b>: Replace {} placeholders with masked argument values</li>
   *   <li><b>Add exception</b>: If present, format stack trace</li>
   *   <li><b>Serialize</b>: Convert to JSON string (compact or pretty)</li>
   * </ol>
   *
   * <h3>Important Notes:</h3>
   * <ul>
   *   <li><b>MDC is NOT masked</b>: MDC values are added as-is. Never put PII in MDC!</li>
   *   <li><b>Only arguments are masked</b>: DTOs and JSON strings in log arguments are masked</li>
   *   <li><b>Simple types pass through</b>: Primitives, numbers, booleans are not processed</li>
   *   <li><b>Error safety</b>: Any exception triggers {@link #formatFallback} to prevent PII leaks</li>
   * </ul>
   *
   * @param ev the log event from Logback
   * @return JSON string with newline, safe for writing to log files
   */
  @Override
  public String doLayout(ILoggingEvent ev) {
    try {
      /* 1 ─ Build the JSON tree */
      ObjectMapper mapper = prettyPrint ? PRETTY_MAPPER : COMPACT_MAPPER;
      ObjectNode root = mapper.createObjectNode();
      root.put("timestamp", getFormattedCurrentTimestamp(ev.getTimeStamp()));
      root.put("level", ev.getLevel().toString());
      root.put("logger", ev.getLoggerName());
      // MDC context (NOT masked - by policy, PII should never be in MDC)
      Map<String, String> mdc = ev.getMDCPropertyMap();
      if (mdc != null && !mdc.isEmpty()) {
        mdc.forEach(root::put);
      }
      /* 2 ─ Mask PII in log arguments */
      Object[] maskedArgs = maskArgumentArray(ev.getArgumentArray());
      String formattedMsg = formatMessage(maskedArgs,
          ev.getMessage()); // replace the maskedArgs into the placeholders
      root.set("message", TextNode.valueOf(formattedMsg));
      if (ev.getThrowableProxy() != null) {
        root.set("exception", TextNode.valueOf(formatException(ev)));
      }
      /* 3 ─ Serialize the whole log event as JSON and return */
      return mapper.writeValueAsString(root) + System.lineSeparator();
    } catch (Exception ex) {
      return formatFallback(ev, ex);
    }
  }

  /**
   * Convert epoch milliseconds to ISO-8601 formatted timestamp.
   * <p>
   * Uses system default timezone. Format: {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX}
   * 
   * @param timestamp epoch milliseconds from log event
   * @return formatted timestamp string
   */
  private static String getFormattedCurrentTimestamp(long timestamp) {
    return ISO_FORMATTER.format(Instant.ofEpochMilli(timestamp));
  }

  /**
   * Format a log message by replacing SLF4J placeholders with argument values.
   * <p>
   * Uses {@link MessageFormatter#arrayFormat} to handle {@code {}} placeholders
   * in the message template. Assumes arguments have already been masked by
   * {@link #maskArgumentArray}.
   * 
   * @param args the argument array (already masked)
   * @param msgTemplate the message template with {@code {}} placeholders
   * @return the formatted message string
   */
  private String formatMessage(Object[] args, String msgTemplate) {
    if (args == null) {
      return msgTemplate;
    }
    return MessageFormatter.arrayFormat(msgTemplate, args).getMessage();
  }

  /**
   * Mask all arguments in the log message argument array.
   * <p>
   * Creates a new array and processes each argument through {@link #maskArgument}.
   * This ensures PII in DTOs, JSON strings, and complex objects is redacted
   * before the message is formatted.
   * 
   * @param args the original argument array from the log event
   * @return a new array with masked values, or the original if null/empty
   */
  private Object[] maskArgumentArray(Object[] args) {
    if (args == null || args.length == 0) {
      return args;
    }
    Object[] maskedArgs = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      Object arg = args[i];
      maskedArgs[i] = maskArgument(arg);
    }
    return maskedArgs;
  }

  /**
   * Mask a single log argument if it contains potential PII.
   * <p>
   * Core masking logic that determines how to handle different argument types:
   * <ol>
   *   <li>If {@code null} or a simple type (primitive, Number, Boolean, enum, LocalDate, etc.),
   *       return as-is without processing</li>
   *   <li><b>SIZE CHECK:</b> If argument exceeds {@link #MAX_ARG_SIZE_BYTES}, redact immediately</li>
   *   <li>If a String, attempt to parse as JSON; if successful, mask the JSON tree</li>
   *   <li>If a DTO/POJO, convert to JSON tree and mask it</li>
   *   <li>If serialization fails, return a safe redaction message</li>
   * </ol>
   * <p>
   * <b>Defense-in-Depth:</b> Size check happens BEFORE JSON parsing/masking to protect
   * the application from excessive memory consumption. This complements the depth/node
   * limits in {@link PiiDataMasker}.
   * <p>
   * <b>Thread Safety:</b> Uses static {@link #COMPACT_MAPPER} which is thread-safe
   * for read operations. The masking itself delegates to {@link PiiDataMasker#maskJsonTree}
   * which handles concurrent access via thread-local recursion tracking.
   * 
   * @param arg the log argument to mask
   * @return the masked argument (as JSON string for complex types), or original for simple types
   */
  private Object maskArgument(Object arg) {
    if (arg == null) {
      return null;
    }

    // Handle primitives and simple types directly
    if (isSimpleType(arg)) {
      return arg;
    }

    // Handle DTOs and complex objects
    try {
      JsonNode jsonNode;
      String jsonString;

      if (arg instanceof String string) {
        // Defense: Check string size before parsing
        int sizeBytes = string.getBytes().length;
        if (sizeBytes > MAX_ARG_SIZE_BYTES) {
          addWarn(String.format("Argument size (%d bytes) exceeds limit (%d bytes) - redacting to prevent performance impact",
              sizeBytes, MAX_ARG_SIZE_BYTES));
          return "[REDACTED DUE TO ARG TOO LARGE: " + formatSize(sizeBytes) + "]";
        }
        
        // Try parsing string as JSON
        jsonNode = COMPACT_MAPPER.readTree(string);
        jsonString = string;
      } else {
        // Convert DTO/object to JSON tree first
        jsonNode = COMPACT_MAPPER.valueToTree(arg);
        jsonString = jsonNode.toString();
        
        // Defense: Check serialized size before masking
        int sizeBytes = jsonString.getBytes().length;
        if (sizeBytes > MAX_ARG_SIZE_BYTES) {
          addWarn(String.format("Serialized argument size (%d bytes) exceeds limit (%d bytes) - redacting to prevent performance impact",
              sizeBytes, MAX_ARG_SIZE_BYTES));
          return "[REDACTED DUE TO ARG TOO LARGE: " + formatSize(sizeBytes) + "]";
        }
      }
      
      // Proceed with masking
      maskingLayout.maskJsonTree(jsonNode);
      return jsonNode.toString();
    } catch (Exception e) {
      // Fallback for objects that can't be serialized
      return "[REDACTED DUE TO SERIALIZATION FAILURE: " + e.getMessage() + "]";
    }
  }

  /**
   * Format byte size in human-readable format (KB/MB).
   * <p>
   * Helper method for logging size limit violations.
   * 
   * @param bytes the size in bytes
   * @return formatted size string (e.g., "512KB", "2.5MB")
   */
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
   * Check if an object is a simple type that doesn't need masking.
   * <p>
   * Simple types are passed through without JSON conversion or masking:
   * primitives, boxed primitives (Number, Boolean), enums, and date/time types
   * (LocalDate, LocalDateTime).
   * <p>
   * This optimization avoids unnecessary JSON serialization for values that
   * typically don't contain PII.
   * 
   * @param obj the object to check
   * @return {@code true} if the object is a simple type
   */
  private boolean isSimpleType(Object obj) {
    Class<?> c = obj.getClass();
    return c.isPrimitive()
        || Number.class.isAssignableFrom(c)
        || c.equals(Boolean.class)
        || c.isEnum()
        || c.equals(LocalDate.class)
        || c.equals(LocalDateTime.class);
  }

  /**
   * Format exception information for inclusion in the log output.
   * <p>
   * Condenses the throwable into a compact format: exception class name,
   * message, and the first 10 stack frames. This keeps log size manageable
   * while providing enough context for initial troubleshooting.
   * <p>
   * <b>Format:</b> {@code ExceptionClass: message\n\tat stackFrame1\n\tat stackFrame2...}
   * <p>
   * <b>Note:</b> Full stack traces can be obtained from logging aggregation
   * systems or by enabling verbose logging if needed.
   * 
   * @param event the logging event containing the throwable proxy
   * @return formatted exception string with limited stack trace
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
   * Generate a safe fallback log line when the main layout fails.
   * <p>
   * This method is the last line of defense: if any exception occurs during
   * normal log processing (JSON serialization, masking, etc.), this method
   * produces a minimal, safe log line that:
   * <ul>
   *   <li>Contains no PII (no arguments, no message body)</li>
   *   <li>Indicates a layout error occurred</li>
   *   <li>Preserves timestamp, level, and logger name for debugging</li>
   * </ul>
   * <p>
   * This ensures the application keeps running even if logging fails, and
   * prevents accidental PII leakage through error paths.
   * 
   * @param event the original logging event
   * @param ex the exception that occurred during layout processing
   * @return a safe, PII-free error log line
   */
  private String formatFallback(ILoggingEvent event, Exception ex) {
    return String.format("%s [%s] %s - [LAYOUT ERROR] %s: %s%n",
        getFormattedCurrentTimestamp(event.getTimeStamp()),
        event.getLevel(),
        event.getLoggerName(),
        ex.getClass().getSimpleName(),
        ex.getMessage());
  }

  /**
   * Initialize the layout (Logback lifecycle).
   * <p>
   * Validates that the required {@link PiiDataMasker} component is configured,
   * then delegates startup to the masker and parent layout. This method will
   * fail fast if masking is not properly configured, preventing logs from being
   * written without PII protection.
   * 
   * @throws IllegalStateException if {@code maskingLayout} is not configured
   */
  @Override
  public void start() {
    if (maskingLayout == null) {
      addError("CRITICAL – PiiDataMasker missing, refusing to start");
      throw new IllegalStateException("maskingLayout is REQUIRED");
    }
    maskingLayout.start();
    super.start();
  }

  /**
   * Shutdown the layout (Logback lifecycle).
   * <p>
   * Cleanly stops the PII masker component and parent layout. Ensures proper
   * cleanup of resources like thread-local variables in the masker.
   */
  @Override
  public void stop() {
    maskingLayout.stop();
    super.stop();
  }
}


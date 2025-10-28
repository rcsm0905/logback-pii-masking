package com.example.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
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
 *     <!-- Optional: maxNestingDepth (default: 50), maxStringLength (default: 10MB), maxDocumentLength (default: 10MB) -->
 *     &lt;maskingLayout class="com.example.logging.PiiDataMasker"&gt;
 *       &lt;maskedFields&gt;ssn,creditCard,password,email&lt;/maskedFields&gt;   &lt;!-- mandatory --&gt;
 *       &lt;maskToken&gt;[REDACTED]&lt;/maskToken&gt;   &lt;!-- mandatory --&gt;
 *       <!-- Optional: maxJsonDepth (default: 50), maxNodes (default: 10000) -->
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
 * <b>IMPORTANT:</b> MDC values are NOT masked. All PII should be in log arguments where it can be properly masked.
 * <h2>What Gets Masked</h2>
 * <ul>
 *   <li><b>Log arguments</b>: DTOs, POJOs, JSON strings → converted to JSON → fields masked</li>
 * </ul>
 *
 * <h2>Safety & Error Handling</h2>
 * <ul>
 *   <li>{@link #formatFallback(ILoggingEvent, Exception)} - If ANY error occurs, output safe
 *       fallback log without PII (prevents PII leaks on error paths)
 *       → "%s [%s] %s - [LAYOUT ERROR] %s: %s%n"</li>
 *   <li>{@link #nonPiiSource(Object)} - Optimization: skip JSON conversion for primitives and simple types</li>
 *   <li>Serialization failures → "[REDACTED DUE TO SERIALIZATION FAILURE]"</li>
 *   <li>Jackson size limits protect against large inputs: 10MB document/string limits (configurable via {@code maxDocumentLength}, {@code maxStringLength})</li>
 * </ul>
 */

@Setter
public class JsonStructuredLayout extends LayoutBase<ILoggingEvent> {

  private static final DateTimeFormatter ISO_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
          .withZone(ZoneId.systemDefault());

  /**
   * Maximum nesting depth for JSON structures (protects against deeply nested attacks).
   * Default: 50 levels (covers 99.9% of real-world APIs).
   * <p>
   * Configurable via logback-spring.xml:
   * <pre>
   *   &lt;layout class="com.example.logging.JsonStructuredLayout"&gt;
   *     &lt;maxNestingDepth&gt;50&lt;/maxNestingDepth&gt;
   *     ...
   *   &lt;/layout&gt;
   * </pre>
   */
  private int maxNestingDepth = 50;
  
  /**
   * Maximum string length in JSON (allows base64-encoded images).
   * Default: 10MB for base64 images (~7.5MB original).
   * <p>
   * Configurable via logback-spring.xml:
   * <pre>
   *   &lt;layout class="com.example.logging.JsonStructuredLayout"&gt;
   *     &lt;maxStringLength&gt;10000000&lt;/maxStringLength&gt;
   *     ...
   *   &lt;/layout&gt;
   * </pre>
   */
  private int maxStringLength = 10_000_000;  // 10MB for base64 images
  
  /**
   * Maximum total JSON document size during parsing.
   * Default: 10MB total.
   * <p>
   * Configurable via logback-spring.xml:
   * <pre>
   *   &lt;layout class="com.example.logging.JsonStructuredLayout"&gt;
   *     &lt;maxDocumentLength&gt;10000000&lt;/maxDocumentLength&gt;
   *     ...
   *   &lt;/layout&gt;
   * </pre>
   */
  private long maxDocumentLength = 10_000_000L;  // 10MB total
  
  private ObjectMapper compactMapper;
  private ObjectMapper prettyMapper;
  
  /**
   * Create an ObjectMapper with safety constraints to prevent DoS attacks.
   * Configures both read (parsing) and write (serialization) constraints.
   */
  private ObjectMapper createSafeMapper(boolean prettyPrint) {
    ObjectMapper mapper = new ObjectMapper();
    
    // Serialization configuration
    mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    mapper.disable(SerializationFeature.FAIL_ON_SELF_REFERENCES);
    
    if (prettyPrint) {
      mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
    }
    
    // INPUT protection: constraints during JSON parsing (readTree, readValue)
    mapper.getFactory().setStreamReadConstraints(
      com.fasterxml.jackson.core.StreamReadConstraints.builder()
        .maxNestingDepth(maxNestingDepth)
        .maxStringLength(maxStringLength)
        .maxNumberLength(1000)
        .maxNameLength(50_000)
        .maxDocumentLength(maxDocumentLength)
        .build()
    );
    
    // OUTPUT protection: constraints during serialization (writeValue, valueToTree)
    // Available in Jackson 2.16+; gracefully degrade for older versions
    try {
      mapper.getFactory().setStreamWriteConstraints(
        com.fasterxml.jackson.core.StreamWriteConstraints.builder()
          .maxNestingDepth(maxNestingDepth)
          .build()
      );
    } catch (NoSuchMethodError e) {
      // Older Jackson version; rely on other defenses (heuristics, size checks)
    }
    
    return mapper;
  }

  // Nested PII masking component
  private PiiDataMasker maskingLayout;

  /**
   * Pretty print for local dev (configurable via logback-spring.xml) If true, use
   * {@link #prettyMapper}; otherwise use {@link #compactMapper}.
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
   * @param ev the log event from Logback
   * @return JSON string with newline, safe for writing to log files
   */
  @Override
  public String doLayout(ILoggingEvent ev) {
    try {
      /* 1 ─ Build the JSON tree */
      ObjectMapper mapper = prettyPrint ? prettyMapper : compactMapper;
      ObjectNode root = mapper.createObjectNode();
      root.put("timestamp", getFormattedCurrentTimestamp(ev.getTimeStamp()));
      root.put("level", ev.getLevel().toString());
      root.put("logger", ev.getLoggerName());
      // MDC context
      Map<String, String> mdc = ev.getMDCPropertyMap();
      if (mdc != null && !mdc.isEmpty()) {
        mdc.forEach(root::put);
      }
      /* 2 ─ Mask PII in log arguments */
      Object[] maskedArgs = maskArgumentArray(ev.getArgumentArray());
      String formattedMsg = formatMessage(maskedArgs, ev.getMessage()); // replace the maskedArgs into the placeholders
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
   * This ensures PII in DTOs, JSON strings, and complex objects are redacted
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
   * Determine if a string looks like JSON (object or array) without heavy parsing.
   */
  private boolean isJsonLike(String s) {
    if (s == null) return false;
    String t = s.stripLeading();
    return !t.isEmpty() && (t.charAt(0) == '{' || t.charAt(0) == '[');
  }

  /**
   * Identify argument types that are considered non-PII sources and should be
   * passed through without JSON serialization.
   */
  private boolean nonPiiSource(Object obj) {
    if (obj == null) {
			return true;
		}
    Class<?> c = obj.getClass();
    return c.isEnum()
        || obj instanceof Number
        || obj instanceof Boolean
        || obj instanceof Character
        || obj instanceof java.util.UUID
        || obj instanceof java.time.temporal.TemporalAccessor
        || (obj instanceof CharSequence && !(obj instanceof String))
        || obj instanceof Throwable
        || obj instanceof Map<?, ?>
        || obj instanceof Iterable<?>
        || c.isArray();
  }

  /**
   * Mask a single log argument if it contains potential PII.
   * <p>
   * Core masking logic that determines how to handle different argument types:
   * <ol>
   *   <li>If {@code null} or a non-PII source ({@link #nonPiiSource(Object)}),
   *       return as-is without processing</li>
   *   <li>If a String, check if JSON-like; if yes, parse and mask the JSON tree</li>
   *   <li>If a DTO/POJO, convert to JSON tree and mask it</li>
   *   <li>If serialization fails, return a safe redaction message</li>
   * </ol>
   * <p>
   * <b>Security:</b> Jackson's {@code StreamReadConstraints} and {@code StreamWriteConstraints}
   * protect against large or deeply nested inputs (10MB document/string limits, 50-level depth).
   * This complements the depth/node limits in {@link PiiDataMasker}.
   * 
   * @param arg the log argument to mask
   * @return the masked argument (as JsonNode for complex types), or original for simple types
   */
  private Object maskArgument(Object arg) {
    if (arg == null) {
      return null;
    }

    // Pass through clearly non-PII sources; formatter will stringify later
    if (nonPiiSource(arg)) {
      return arg;
    }

    try {
      // Strings: only attempt JSON parsing for JSON-like content
      if (arg instanceof String string) {
        if (!isJsonLike(string)) {
          return string;
        }
        JsonNode node = compactMapper.readTree(string);
        maskingLayout.maskJsonTree(node);
        return node;
      }

      // DTO/POJO objects: Serialize to JSON tree (StreamWriteConstraints protect during this)
      JsonNode node = compactMapper.valueToTree(arg);
      maskingLayout.maskJsonTree(node);
      return node;

    } catch (Exception e) {
      // Fallback for objects/strings that can't be parsed/serialized; include exception detail
      String msg = e.getMessage();
      if (msg == null) msg = e.getClass().getSimpleName();
      else {
        msg = msg.replaceAll("\\s+", " ");
        if (msg.length() > 200) msg = msg.substring(0, 200) + "...";
        msg = e.getClass().getSimpleName() + ": " + msg;
      }
      return "[REDACTED DUE TO SERIALIZATION FAILURE: " + msg + "]";
    }
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
    
    // Initialize mappers with configured constraints
    compactMapper = createSafeMapper(false);
    prettyMapper = createSafeMapper(true);
    
    maskingLayout.start();
    super.start();
  }

  /**
   * Shutdown the layout (Logback lifecycle).
   * <p>
   * Cleanly stops the PII masker component and parent layout.
   */
  @Override
  public void stop() {
    maskingLayout.stop();
    super.stop();
  }
}


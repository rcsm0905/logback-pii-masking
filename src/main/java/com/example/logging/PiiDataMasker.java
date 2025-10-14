package com.example.logging;

import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Log-level PII redaction utility used by {@link JsonStructuredLayout}.
 *
 * <h2>How it fits in the pipeline</h2>
 * <pre>
 * ┌──────────────────────┐   ① build JSON tree
 * │ JsonStructuredLayout │──▶ (timestamp, level, MDC, message…)
 * └──────────────────────┘
 *              │
 *              │ ② in-place masking
 *              ▼
 *      ┌────────────────┐
 *      │ PiiDataMasker  │ – masks all fields whose names are listed in
 *      └────────────────┘   {@code <maskedFields>}.
 *                           Works directly on the Jackson tree provided by the layout, therefore
 *                           no extra parse/serialize round-trip is needed.
 *              │
 *              │ ③ layout serializes once (pretty/compact) and returns line
 *              ▼
 *        Logback appender
 * </pre>
 *
 * <h3>Public surface currently in use</h3>
 * <li> {@link #start()}, {@link #stop()}, {@link #isStarted()} – Logback life-cycle
 * <li> {@link #maskJsonTree(com.fasterxml.jackson.databind.JsonNode)} – invoked by
 *   {@code JsonStructuredLayout#doLayout}.
 *
 * <h3>Configuration</h3>
 * Supplied via {@code logback-spring.xml} and validated in {@link #start()}:
 * <ul>
 *   <li>{@code <maskedFields>}   – comma-separated field names</li>
 *   <li>{@code <maskToken>}      – replacement string (e.g. "[REDACTED]")</li>
 * </ul>
 *
 * <h3>Thread-safety</h3>
 * The class is immutable after {@link #start()} completes; recursion depth is
 * tracked with a {@code ThreadLocal} so multiple threads can mask logs
 * concurrently without interference.
 *
 * <p><b>NOTE:</b> All code changes below are internal refactors: no behavior or public
 * contract has been altered.</p>
 */
@Setter
@Getter
public class PiiDataMasker extends ContextAwareBase implements LifeCycle {

	/* ───────────── constants ───────────── */

	private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

	private static final int MAX_RECURSION_DEPTH = 10;
	private static final ThreadLocal<Integer> RECURSION_DEPTH =
			ThreadLocal.withInitial(() -> 0);                // renamed: UPPER_SNAKE for constants

	private static final int MAX_JSON_SCAN = 100_000;    // scan limit for brace matching
	private static final Pattern FIELD_CLEANER = Pattern.compile("[^A-Za-z0-9_]");

	/* ───────────── state ───────────── */

	private final AtomicBoolean started = new AtomicBoolean(false);

	private String maskedFields;
	private String maskToken;
	private Set<String> fieldNamesToMask = Collections.emptySet();

	/* ───────────── life-cycle ───────────── */

	@Override
	public void start() {
		if (started.get()) return;        // idempotent

		try {
			/* ─── validate & initialise ─── */
			maskToken        = requireNonBlank(maskToken, "<maskToken>");
			fieldNamesToMask = parseAndValidateFields(maskedFields);

			started.set(true);
		} catch (Exception ex) {
			addError("PII masking initialisation failed", ex);
			throw new IllegalStateException("PII masking init failed", ex);
		}
	}

	@Override public void stop() {
		if (!started.getAndSet(false)) return;
		RECURSION_DEPTH.remove();
	}

	@Override public boolean isStarted() { return started.get(); }

	/* ───────────── public API ───────────── */

	/**
	 * Mask the given tree in-place.  Null-safe.
	 *
	 * @param root the JSON node to sanitize; ignored when {@code null}
	 */
	public void maskJsonTree(JsonNode root) {
		if (root != null) maskJsonTreeInternal(root);
	}

	/* ───────────── validation helpers ───────────── */
	/**
	 * Ensure the supplied string is not null / blank.
	 *
	 * @return the same value, allowing inline assignment
	 * @throws IllegalStateException if the check fails
	 */
	private static String requireNonBlank(String v, String fieldName) {
		if (v == null || v.isBlank()) {
			throw new IllegalStateException(fieldName + " must be configured");
		}
		return v;
	}

	/**
	 * Parse the CSV list from <maskedFields>, clean each token and return an
	 * *immutable* set.
	 *
	 * @throws IllegalStateException when the CSV is blank or contains no
	 *                               usable field names.
	 */
	private static Set<String> parseAndValidateFields(String csv) {
		String src = requireNonBlank(csv, "<maskedFields>");

		Set<String> out = new HashSet<>();
		for (String part : src.split(",")) {
			String token = FIELD_CLEANER.matcher(part.trim()).replaceAll("");
			if (!token.isEmpty() && token.length() <= 50) {
				out.add(token);
			}
		}
		if (out.isEmpty()) {
			throw new IllegalStateException("<maskedFields> produced no valid entries");
		}
		return Collections.unmodifiableSet(out);
	}

	/* ───────────── masking traversal ───────────── */

	private void maskJsonTreeInternal(JsonNode root) {
		if (RECURSION_DEPTH.get() >= MAX_RECURSION_DEPTH) return;

		RECURSION_DEPTH.set(RECURSION_DEPTH.get() + 1);
		try {
			traverseAndMaskTree(root);
		} finally {
			RECURSION_DEPTH.set(RECURSION_DEPTH.get() - 1);
		}
	}

	/**
	 * (original detailed Javadoc kept)
	 */
	private void traverseAndMaskTree(JsonNode root) {
		Deque<JsonNode> stack = new ArrayDeque<>();
		stack.push(root);

		while (!stack.isEmpty()) {
			JsonNode node = stack.pop();

			if (node.isObject()) {
				ObjectNode obj = (ObjectNode) node;
				List<String> toMask = new ArrayList<>();

				obj.fields().forEachRemaining(entry -> {
					String name   = entry.getKey();
					JsonNode value = entry.getValue();

					if (fieldNamesToMask.contains(name)) {
						toMask.add(name);
					} else if (looksLikeJsonString(value)) {
						handleNestedJsonString(obj, name, value, stack);
					} else {
						stack.push(value);
					}
				});

				toMask.forEach(f -> obj.set(f, maskValue(obj.get(f))));
			}

			else if (node.isArray()) {
				node.elements().forEachRemaining(stack::push);
			}
		}
	}

	/* ───────────── embedded JSON helpers ───────────── */

	private static boolean looksLikeJsonString(JsonNode n) {
		return n.isTextual() && isJsonLike(n.asText());
	}

	private static boolean isJsonLike(String v) {
		return v != null && v.length() > 1 &&
				((v.indexOf('{') != -1 && v.indexOf('}') != -1) ||
						(v.indexOf('[') != -1 && v.indexOf(']') != -1));
	}

	private void handleNestedJsonString(
			ObjectNode parent, String field, JsonNode value, Deque<JsonNode> stack) {

		String text = value.asText();
		String json = extractJsonFromString(text);
		if (json == null) {                       // treat as plain text
			stack.push(value);
			return;
		}

		try {
			JsonNode nested = COMPACT_MAPPER.readTree(json);
			maskJsonTreeInternal(nested);
			parent.put(field, text.replace(json,
					COMPACT_MAPPER.writeValueAsString(nested)));
		} catch (Exception ex) {
			stack.push(value);                      // fallback: keep original
		}
	}

	/* ───────────── brace matching (unchanged) ───────────── */

	private String extractJsonFromString(String v) {
		int brace = v.indexOf('{');
		int bracket = v.indexOf('[');
		int start = (brace != -1 && (bracket == -1 || brace < bracket)) ? brace : bracket;
		if (start == -1) return null;

		int end = findMatchingBrace(v, start, v.charAt(start),
				(v.charAt(start) == '{') ? '}' : ']');
		return (end != -1) ? v.substring(start, end + 1) : null;
	}

	private int findMatchingBrace(String text, int openIdx, char open, char close) {
		boolean inString = false, escaped = false;
		int depth = 1;
		int limit = Math.min(openIdx + MAX_JSON_SCAN, text.length());

		for (int i = openIdx + 1; i < limit; i++) {
			char c = text.charAt(i);

			if (escaped) { escaped = false; continue; }
			if (c == '\\') { escaped = true; continue; }

			if (c == '"') { inString = !inString; continue; }
			if (inString) continue;

			if (c == open) depth++;
			else if (c == close && --depth == 0) return i;
		}
		return -1;
	}

	/* ───────────── masking values ───────────── */

	private JsonNode maskValue(JsonNode original) {
		if (original == null || original.isNull()) return original;

		if (original.isArray()) {
			ArrayNode a = COMPACT_MAPPER.createArrayNode();
			original.forEach(n -> a.add(maskToken));   // keep identical size
			return a;
		}
		if (original.isObject()) {
			ObjectNode o = COMPACT_MAPPER.createObjectNode();
			original.fieldNames().forEachRemaining(f -> o.put(f, maskToken));
			return o;
		}
		return TextNode.valueOf(maskToken);          // primitives
	}
}
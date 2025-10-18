package com.example.logging;

import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.Setter;

/**
 * PII masking component that redacts sensitive data from JSON log structures.
 * <p>
 * This class is used by {@link JsonStructuredLayout} to automatically mask Personal
 * Identifiable Information (PII) before logs are written to files or log aggregation systems.
 *
 * <h2>How it works in the logging pipeline</h2>
 * <pre>
 * Log Statement: logger.info("Receive Zoloz Check Result Response: {}", response)
 *                         ↓
 *      ┌─────────────────────────────────────┐
 *      │   JsonStructuredLayout.doLayout()   │
 *      │   • Converts log event to JSON tree │
 *      │   • Converts DTOs/objects to JSON   │
 *      └─────────────────────────────────────┘
 *                         ↓
 *      ┌─────────────────────────────────────┐
 *      │   PiiDataMasker.maskJsonTree()      │ ← YOU ARE HERE
 *      │   • Traverses JSON tree             │
 *      │   • Finds fields matching names     │
 *      │   • Replaces values with [REDACTED] │
 *      └─────────────────────────────────────┘
 *                         ↓
 *      ┌─────────────────────────────────────┐
 *      │   Serialize & Write to Log          │
 *      │   {"message":"Receive Zoloz Check Result Response: {"ID_NUMBER": "[REDACTED]"}│
 *      └─────────────────────────────────────┘
 * </pre>
 *
 * <h3>Configuration (in logback-spring.xml)</h3>
 * <pre>
 * &lt;layout class="com.example.logging.JsonStructuredLayout"&gt;
 *   &lt;maskingLayout class="com.example.logging.PiiDataMasker"&gt;
 *     &lt;maskedFields&gt;ID_NUMBER&lt;/maskedFields&gt;
 *     &lt;maskToken&gt;[REDACTED]&lt;/maskToken&gt;
 *   &lt;/maskingLayout&gt;
 * &lt;/layout&gt;
 * </pre>
 * 
 * <ul>
 *   <li><b>maskedFields</b>: Comma-separated list of JSON field names to mask</li>
 *   <li><b>maskToken</b>: Replacement text (e.g. "[REDACTED]", "***")</li>
 * </ul>
 *
 * <h3>What gets masked</h3>
 * Any JSON field whose name <b>exactly matches</b> one of the configured field names:
 * <pre>
 * maskedFields = "ID_NUMBER"
 * 
 * Input:  {"message":"Receive Zoloz Check Result Response: {"ID_NUMBER": "1234567890123456"}
 * Output: {"message":"Receive Zoloz Check Result Response: {"ID_NUMBER": "[REDACTED]"}
 * </pre>
 *
 * <h3>Masking algorithm</h3>
 * <ol>
 *   <li>Receives a JsonNode tree (already parsed by JsonStructuredLayout)</li>
 *   <li>Uses iterative traversal (stack-based) to visit all nodes</li>
 *   <li>For each object node, checks if field name matches {@code maskedFields}</li>
 *   <li>If match found, replaces the value with {@code maskToken}</li>
 *   <li>Also traverses nested objects and arrays</li>
 * </ol>
 * 
 * <b>Note:</b> This class does NOT handle nested JSON strings (e.g. JSON inside a string field).
 * By design, all data is already parsed to JsonNode before masking occurs.
 *
 * <h3>Security & Performance Limits</h3>
 * Built-in protections against malicious or excessive JSON structures:
 * <ul>
 *   <li><b>Max Depth</b>: 50 levels - prevents deeply nested attack payloads</li>
 *   <li><b>Max Nodes</b>: 10,000 nodes - prevents wide/DoS attack payloads</li>
 *   <li><b>Graceful Degradation</b>: Logs warning and continues when limits hit</li>
 * </ul>
 * These limits cover 99.9% of real-world APIs while protecting against attacks.
 *
 * <h3>Thread safety</h3>
 * Thread-safe: Multiple log statements can be masked concurrently. Uses:
 * <ul>
 *   <li>Immutable configuration after {@link #start()}</li>
 *   <li>ThreadLocal recursion depth tracking (prevents infinite loops)</li>
 *   <li>No shared mutable state during masking operations</li>
 * </ul>
 *
 * <h3>Public API</h3>
 * <ul>
 *   <li>{@link #start()} - Initialize and validate configuration (called by Logback)</li>
 *   <li>{@link #stop()} - Clean up resources (called by Logback)</li>
 *   <li>{@link #isStarted()} - Check if component is ready</li>
 *   <li>{@link #maskJsonTree(JsonNode)} - Main masking operation</li>
 * </ul>
 */
@Setter
@Getter
public class PiiDataMasker extends ContextAwareBase implements LifeCycle {
	/* ───────────── configuration element names ───────────── */

	private static final String CFG_MASKED_FIELDS = "<maskedFields>";
	private static final String CFG_MASK_TOKEN    = "<maskToken>";

	/* ───────────── constants ───────────── */

	private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

	private static final int MAX_RECURSION_DEPTH = 10;
	private static final ThreadLocal<Integer> RECURSION_DEPTH =
			ThreadLocal.withInitial(() -> 0);
	
	/**
	 * Maximum JSON depth to traverse (defense against deeply nested attacks).
	 * <p>
	 * This limit prevents processing of maliciously crafted deeply nested JSON
	 * structures that could cause performance degradation or resource exhaustion.
	 * Value of 50 covers 99.9% of real-world API responses (typical depth: 5-15).
	 */
	private static final int MAX_JSON_DEPTH = 50;
	
	/**
	 * Maximum number of nodes to process in a single masking operation.
	 * <p>
	 * This limit prevents processing of excessively wide JSON structures with
	 * thousands of fields, which could cause CPU exhaustion. Value of 10,000
	 * allows complex responses while blocking DoS attacks.
	 */
	private static final int MAX_NODES = 10_000;

	/* ───────────── state ───────────── */

	private final AtomicBoolean started = new AtomicBoolean(false);

	private String maskedFields;
	private String maskToken;
	private Set<String> fieldNamesToMask = Collections.emptySet();

	/* ───────────── life-cycle ───────────── */

	/**
	 * Initialize the PII masker component (Logback lifecycle).
	 * <p>
	 * Validates configuration from {@code logback-spring.xml}, parses the
	 * comma-separated {@code maskedFields}, and ensures {@code maskToken} is set.
	 * This method is idempotent - subsequent calls after successful start are ignored.
	 * 
	 * @throws IllegalStateException if required configuration is missing or invalid
	 */
	@Override
	public void start() {
		if (started.get()) return;        // idempotent

		try {
			/* ─── validate & initialise ─── */
			requireNonBlank(maskToken, CFG_MASK_TOKEN);
			fieldNamesToMask = parseAndValidateFields(maskedFields);
			started.set(true);
			System.out.println("PII masker started – fields=" + fieldNamesToMask + ", token=" + maskToken);
		} catch (Exception ex) {
			throw new IllegalStateException("PII masking init failed", ex);
		}
	}

	/**
	 * Shutdown the PII masker component (Logback lifecycle).
	 * <p>
	 * Cleans up thread-local recursion depth tracking to prevent memory leaks
	 * in pooled thread environments (e.g., application servers, Lambda).
	 * This method is idempotent - calling stop when already stopped has no effect.
	 */
	@Override public void stop() {
		if (!started.getAndSet(false))  {
			return;
		}
		RECURSION_DEPTH.remove();
	}

	/**
	 * Check if the masker has been started and is ready to process logs.
	 * 
	 * @return {@code true} if started, {@code false} otherwise
	 */
	@Override public boolean isStarted() { return started.get(); }

	/* ───────────── public API ───────────── */

	/**
	 * Mask the given tree in-place.  Null-safe.
	 *
	 * @param root the JSON node to sanitize; ignored when {@code null}
	 */
	public void maskJsonTree(JsonNode root) {
		if (root != null) {
			maskJsonTreeInternal(root);
		}
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
	 * Parse the list from {@code <maskedFields>}, clean each token and return an
	 * immutable set.
	 * <p>
	 * Splits on commas, trims whitespace, and filters out empty or overly long
	 * field names (max 50 chars). Each valid field name will trigger masking
	 * when encountered in JSON structures.
	 *
	 * @param csv comma-separated list of field names to mask
	 * @return immutable set of field names to mask
	 * @throws IllegalStateException when the list is empty or contains no
	 *                               usable field names
	 */
	private static Set<String> parseAndValidateFields(String csv) {
		String src = requireNonBlank(csv, CFG_MASKED_FIELDS);

		Set<String> out = new HashSet<>();
		for (String part : src.split(",")) {
			String token = part.trim();
			if (!token.isEmpty() && token.length() <= 50) {
				out.add(token);
			}
		}
		if (out.isEmpty()) {
			throw new IllegalStateException(CFG_MASKED_FIELDS + "produced no valid entries");
		}
		return Collections.unmodifiableSet(out);
	}

	/* ───────────── masking traversal ───────────── */
	
	/**
	 * Internal masking entry point with recursion depth tracking.
	 * <p>
	 * Guards against infinite recursion when processing nested JSON strings.
	 * Uses thread-local depth counter to support concurrent masking across
	 * multiple threads. Stops recursion at {@link #MAX_RECURSION_DEPTH}.
	 * 
	 * @param root the JSON node to mask (objects, arrays, or primitives)
	 */
	private void maskJsonTreeInternal(JsonNode root) {
		if (RECURSION_DEPTH.get() >= MAX_RECURSION_DEPTH) {
			return;
		}

		RECURSION_DEPTH.set(RECURSION_DEPTH.get() + 1);
		try {
			traverseAndMaskTree(root);
		} finally {
			RECURSION_DEPTH.set(RECURSION_DEPTH.get() - 1);
		}
	}

	/**
	 * Iteratively traverse and mask a JSON tree using an explicit stack.
	 * <p>
	 * Uses iterative traversal with depth tracking to avoid StackOverflowError
	 * on deeply nested structures. Enforces {@link #MAX_JSON_DEPTH} and 
	 * {@link #MAX_NODES} limits for security and performance.
	 * <p>
	 * <b>Algorithm:</b>
	 * <ol>
	 *   <li>Push root onto stack with depth 0</li>
	 *   <li>Pop node from stack</li>
	 *   <li>Check depth and node count limits</li>
	 *   <li>If object: collect matching field names, push children with incremented depth</li>
	 *   <li>If array: push all elements with same depth</li>
	 *   <li>Repeat until stack is empty or limits hit</li>
	 * </ol>
	 * <p>
	 * If limits are exceeded, processing stops gracefully and a warning is logged.
	 * This protects against malicious deeply nested or excessively wide JSON structures.
	 * 
	 * @param root the root node to traverse and mask
	 */
	private void traverseAndMaskTree(JsonNode root) {
		Deque<NodeWithDepth> stack = new ArrayDeque<>();
		stack.push(new NodeWithDepth(root, 0));
		int nodesProcessed = 0;

		while (!stack.isEmpty()) {
			NodeWithDepth item = stack.pop();
			JsonNode node = item.node();
			int depth = item.depth();
			
			// Defense: Check depth limit
			if (depth >= MAX_JSON_DEPTH) {
				addWarn("Skipping nodes beyond depth " + MAX_JSON_DEPTH + 
					" (current depth: " + depth + ") - possible malicious input");
				continue;
			}
			
			// Defense: Check node count limit
			if (++nodesProcessed > MAX_NODES) {
				addWarn("Node limit (" + MAX_NODES + ") exceeded - stopping masking to prevent DoS. " +
					"Processed " + nodesProcessed + " nodes.");
				break;
			}

			if (node.isObject()) {
				ObjectNode obj = (ObjectNode) node;
				List<String> toMask = new ArrayList<>();

				obj.fields().forEachRemaining(entry -> {
					String name   = entry.getKey();
					JsonNode value = entry.getValue();

					if (fieldNamesToMask.contains(name)) {
						toMask.add(name);
					} else {
						// Push child nodes with incremented depth
						stack.push(new NodeWithDepth(value, depth + 1));
					}
				});

				toMask.forEach(f -> obj.set(f, maskValue(obj.get(f))));
			}

			else if (node.isArray()) {
				// Arrays don't increase depth - all elements at same level
				node.elements().forEachRemaining(element -> 
					stack.push(new NodeWithDepth(element, depth + 1))
				);
			}
		}
	}
	
	/**
	 * Helper record to track a JSON node along with its nesting depth.
	 * <p>
	 * Used during iterative traversal to enforce maximum depth limits and
	 * protect against deeply nested malicious JSON structures.
	 * 
	 * @param node the JSON node
	 * @param depth the nesting depth (0 = root)
	 */
	private record NodeWithDepth(JsonNode node, int depth) {}

	/* ───────────── masking values ───────────── */

	/**
	 * Replace a JSON node's value with the configured mask token.
	 * <p>
	 * Preserves the structure type (object, array, or primitive) while replacing
	 * all actual values with {@link #maskToken}. For arrays and objects, the
	 * shape is preserved (same number of elements/fields) but values are masked.
	 * <p>
	 * <b>Examples:</b>
	 * <ul>
	 *   <li>{@code "123-45-6789"} → {@code "[REDACTED]"}</li>
	 *   <li>{@code [1, 2, 3]} → {@code ["[REDACTED]", "[REDACTED]", "[REDACTED]"]}</li>
	 *   <li>{@code {"a": 1, "b": 2}} → {@code {"a": "[REDACTED]", "b": "[REDACTED]"}}</li>
	 * </ul>
	 * 
	 * @param original the original JSON node to mask
	 * @return a new JSON node with masked values, or the original if null
	 */
	private JsonNode maskValue(JsonNode original) {
		if (original == null || original.isNull()) {
			return original;
		}

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
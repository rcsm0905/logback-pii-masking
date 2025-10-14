package com.example.logging;

import ch.qos.logback.core.spi.ContextAwareBase;
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
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * Utility class for masking PII in log messages
 * Uses Jackson ObjectMapper for tree-based traversal to handle unlimited nesting depth
 * 
 * NOTE: Uses Logback's status API (addError only) for critical security errors
 * to prevent infinite recursion (masking logs would trigger more masking)
 */
@Setter
@Getter
public class PiiDataMasker extends ContextAwareBase {

	private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();
	private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
		.configure(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT, true);
	
	private static final int MAX_RECURSION_DEPTH = 10; // Prevent infinite recursion on nested JSON strings
	
	// Thread-local recursion depth tracker (for nested JSON string handling)
	private static final ThreadLocal<Integer> recursionDepth = ThreadLocal.withInitial(() -> 0);

	// Configuration properties
	private String maskedFields;
	private String maskToken = "[REDACTED]";
	private int maxMessageSize; // Set via logback-spring.xml (default: 1MB)
	private boolean prettyPrint = false; // Pretty-print output for local dev

	// Parsed field sets for O(1) lookup
	private Set<String> fieldNamesToMask = Collections.emptySet();
	

	public void start() {
		try {
			// Parse and validate configuration
			this.fieldNamesToMask = new HashSet<>(parseFieldsList(this.maskedFields));

			// Validate that at least some masking is configured
			validateConfiguration();

		// Validate maxMessageSize is properly configured
		if (maxMessageSize <= 0) {
			throw new IllegalStateException(
				"maxMessageSize must be configured and > 0. Current value: " + maxMessageSize + 
				". Configure in logback-spring.xml: <maxMessageSize>1000000</maxMessageSize>"
			);
		}

		// PII masking initialized successfully - no need to log this as it's not actionable

		} catch (Exception e) {
			// CRITICAL: Fail-fast to prevent PII exposure
			// DO NOT allow application to start without masking
			addError("CRITICAL: Failed to initialize PII masking patterns. Application cannot start safely.", e);
			throw new IllegalStateException(
				"CRITICAL: PII masking initialization failed. " +
				"Application MUST NOT start to prevent data leakage. " +
				"Error: " + e.getMessage(), e
			);
		}
	}
	
	public void stop() {
		// Cleanup if needed
	}

	private void validateConfiguration() {
		if (fieldNamesToMask.isEmpty()) {
			throw new IllegalStateException(
					"No masking patterns configured. This could lead to PII data exposure. " +
							"Configure maskedFields."
			);
		}
	}

	private static List<String> parseFieldsList(String fields) {
		if (fields == null || fields.trim().isEmpty()) {
			return Collections.emptyList();
		}

		String[] parts = fields.split("[,;\\s]+");
		List<String> sanitized = new ArrayList<>();

		for (String part : parts) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) continue;

			// Sanitize field names - allow only alphanumeric and underscore
			String clean = trimmed.replaceAll("[^A-Za-z0-9_]", "");
			if (!clean.isEmpty() && clean.length() <= 50) { // Limit field name length
				sanitized.add(clean);
			}
		}

		return sanitized;
	}


	/**
	 * Main entry point for masking sensitive data in log messages
	 * Uses Jackson tree traversal for unlimited nesting depth support
	 * 
	 * @param message The log message to mask
	 * @return The masked log message
	 */
	public String maskSensitiveDataOptimized(String message) {
		if (message == null || message.isEmpty()) {
			return message;
		}

		// Prevent processing extremely large messages that could cause issues
		// Default 1MB limit is acceptable for messages containing base64 images
		// Configurable via maxMessageSize in logback-spring.xml
		if (message.length() > maxMessageSize) {
			// Message too large - redact entire log for safety (no need to log this)
			return "[LOG TOO LARGE - REDACTED FOR SAFETY - SIZE: " + message.length() + 
				" bytes, LIMIT: " + maxMessageSize + " bytes]";
		}

		try {
			// Parse JSON into tree structure
			JsonNode rootNode = COMPACT_MAPPER.readTree(message);
			
			// Traverse and mask PII fields at any depth (iterative, no recursion limit)
			maskJsonTree(rootNode);
			
			// Serialize back to JSON string (pretty or compact based on configuration)
			ObjectMapper outputMapper = prettyPrint ? PRETTY_MAPPER : COMPACT_MAPPER;

			return outputMapper.writeValueAsString(rootNode);

		} catch (Exception e) {
			// SECURITY: Never return original message on error - could expose PII
			// Use Logback status API to prevent infinite recursion
			addError("SECURITY ALERT: PII masking failed. Log entry REDACTED for safety. Error: " + e.getMessage(), e);
			
			// Return safe error message with troubleshooting info (NO original message)
			return "[MASKING ERROR - LOG REDACTED FOR SAFETY] Error: " + e.getMessage() + 
				" | Check Logback status for stack trace | Timestamp: " + System.currentTimeMillis();
		}
	}

	/**
	 * Main entry point for tree masking with recursion depth tracking
	 */
	private void maskJsonTree(JsonNode rootNode) {
		if (shouldStopRecursion()) {
			return;
		}
		
		recursionDepth.set(recursionDepth.get() + 1);
		try {
			traverseAndMaskTree(rootNode);
		} finally {
			recursionDepth.set(recursionDepth.get() - 1);
		}
	}
	
	/**
	 * Check if recursion should stop to prevent StackOverflow
	 */
	private boolean shouldStopRecursion() {
		// Max recursion depth reached - stopping traversal (no need to log this)
		return recursionDepth.get() >= MAX_RECURSION_DEPTH;
	}
	
	/**
	 * Iteratively traverse JSON tree and mask PII fields at any depth
	 * Uses stack-based iteration to avoid recursion limits
	 */
	private void traverseAndMaskTree(JsonNode rootNode) {
		Deque<FieldContext> stack = new ArrayDeque<>();
		stack.push(new FieldContext(rootNode));
		
		while (!stack.isEmpty()) {
			JsonNode node = stack.pop().node;
			
			if (node.isObject()) {
				processObjectNode((ObjectNode) node, stack);
			} else if (node.isArray()) {
				processArrayNode(node, stack);
			}
			// Leaf nodes (primitives, strings) handled by parent
		}
	}
	
	/**
	 * Process all fields in an object node
	 */
	private void processObjectNode(ObjectNode objectNode, Deque<FieldContext> stack) {
		List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
		objectNode.fields().forEachRemaining(entries::add);
		
		for (Map.Entry<String, JsonNode> entry : entries) {
			processField(objectNode, entry.getKey(), entry.getValue(), stack);
		}
	}
	
	/**
	 * Process array node - add all elements to traversal stack
	 */
	private void processArrayNode(JsonNode arrayNode, Deque<FieldContext> stack) {
		arrayNode.elements().forEachRemaining(element -> 
			stack.push(new FieldContext(element)));
	}
	
	/**
	 * Process a single field - mask if needed, or continue traversing
	 */
	private void processField(ObjectNode parent, String fieldName, JsonNode fieldValue, Deque<FieldContext> stack) {
		if (shouldMaskField(fieldName)) {
			parent.set(fieldName, maskValue(fieldName, fieldValue));
		} else if (isNestedJsonString(fieldValue)) {
			handleNestedJsonString(parent, fieldName, fieldValue, stack);
		} else {
			stack.push(new FieldContext(fieldValue));
		}
	}
	
	/**
	 * Check if a field value contains nested JSON that needs parsing
	 */
	private boolean isNestedJsonString(JsonNode node) {
		return node.isTextual() && isJsonString(node.asText());
	}
	
	/**
	 * Handle nested JSON strings - extract, parse, mask, replace
	 */
	private void handleNestedJsonString(ObjectNode parent, String fieldName, JsonNode fieldValue, Deque<FieldContext> stack) {
		String textValue = fieldValue.asText();
		String jsonPortion = extractJsonFromString(textValue);
		
		if (jsonPortion != null) {
			tryParseAndMaskNestedJson(parent, fieldName, textValue, jsonPortion, fieldValue, stack);
		} else {
			stack.push(new FieldContext(fieldValue));
		}
	}
	
	/**
	 * Attempt to parse and mask nested JSON, fall back to traversal if it fails
	 */
	private void tryParseAndMaskNestedJson(ObjectNode parent, String fieldName, String textValue, 
	                                        String jsonPortion, JsonNode fallbackValue, Deque<FieldContext> stack) {
		try {
			JsonNode nested = COMPACT_MAPPER.readTree(jsonPortion);
			maskJsonTree(nested); // Recursive call (depth-limited)
			// Always use compact for nested JSON strings (they're embedded in text)
			String maskedJson = COMPACT_MAPPER.writeValueAsString(nested);
			String maskedText = textValue.replace(jsonPortion, maskedJson);
			parent.put(fieldName, maskedText);
		} catch (Exception e) {
			// Failed to parse nested JSON - fall back to traversal TODO: review this
			stack.push(new FieldContext(fallbackValue));
		}
	}
	
	/**
	 * Check if a string value contains JSON (anywhere in the string)
	 * Handles cases like: "Received Response: {...json...}"
	 */
	private boolean isJsonString(String value) {
		if (value == null || value.length() < 2) {
			return false;
		}
		// Check if string contains JSON structure (look for { or [ followed by })
		return (value.contains("{") && value.contains("}")) ||
		       (value.contains("[") && value.contains("]"));
	}
	
	/**
	 * Extract JSON portion from a text string
	 * Handles: "Prefix text: {\"json\": \"data\"} Suffix"
	 * Returns: {\"json\": \"data\"}
	 */
	private String extractJsonFromString(String value) {
		int jsonStart = -1;
		int jsonEnd = -1;
		
		// Find first { or [
		int braceStart = value.indexOf('{');
		int bracketStart = value.indexOf('[');
		
		if (braceStart != -1 && (bracketStart == -1 || braceStart < bracketStart)) {
			jsonStart = braceStart;
			jsonEnd = findMatchingBrace(value, jsonStart, '{', '}');
		} else if (bracketStart != -1) {
			jsonStart = bracketStart;
			jsonEnd = findMatchingBrace(value, jsonStart, '[', ']');
		}
		
		if (jsonStart != -1 && jsonEnd != -1) {
			return value.substring(jsonStart, jsonEnd + 1);
		}
		
		return null;
	}
	
	/**
	 * Find matching closing brace/bracket
	 * Limited search to prevent performance issues on malformed JSON
	 */
	private int findMatchingBrace(String value, int start, char open, char close) {
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;
		
		// Limit search to 100KB to prevent performance degradation
		int maxSearch = Math.min(start + 100_000, value.length());
		
		for (int i = start; i < maxSearch; i++) {
			char c = value.charAt(i);
			
			if (escaped) {
				escaped = false;
				continue;
			}
			
			if (c == '\\') {
				escaped = true;
				continue;
			}
			
			if (c == '"' && !escaped) {
				inString = !inString;
				continue;
			}
			
			if (!inString) {
				if (c == open) {
					depth++;
				} else if (c == close) {
					depth--;
					if (depth == 0) {
						return i;
					}
				}
			}
		}
		
		return -1; // Not found within the search limit
	}

	/**
	 * Context holder for iterative traversal Simplified to only hold the node reference
	 */
		private record FieldContext(JsonNode node) {

	}
	
	/**
	 * Determine if a field should be masked
	 */
	private boolean shouldMaskField(String fieldName) {
		return fieldNamesToMask.contains(fieldName);
	}

	/**
	 * Mask a JSON value based on the actual data type of the value
	 * Preserves JSON structure while masking sensitive content
	 */
	private JsonNode maskValue(String fieldName, JsonNode value) {
		// Use single masking approach for all fields
		return createMaskedStructureAdvanced(value, maskToken);
	}

	/**
	 * Create a masked representation that preserves the JSON structure of the original value
	 * Optimized version with reduced redundancy
	 *
	 * @param originalValue The original JsonNode to mask
	 * @param token The masking token to use
	 * @return A JsonNode with the same structure but masked content
	 */
	private JsonNode createMaskedStructureAdvanced(JsonNode originalValue, String token) {
		// Handle null/missing values early
		if (originalValue == null || originalValue.isNull()) {
			return originalValue;
		}

		// Handle complex types that need structure preservation
		if (originalValue.isArray()) {
			ArrayNode maskedArray = COMPACT_MAPPER.createArrayNode();
			// Preserve array size by adding masked tokens for each element
			int arraySize = originalValue.size();
			for (int i = 0; i < arraySize; i++) {
				maskedArray.add(token);
			}
			return maskedArray;
		}

		if (originalValue.isObject()) {
			ObjectNode maskedObject = COMPACT_MAPPER.createObjectNode();
			// Preserve object structure by masking each field
			originalValue.fieldNames().forEachRemaining(fieldName ->
					maskedObject.put(fieldName, token)
			);
			return maskedObject;
		}

		// All other types (string, number, boolean, binary, etc.) become masked text
		// This consolidates the redundant TextNode.valueOf(token) calls
		return TextNode.valueOf(token);
	}
	

	// Custom getters for defensive copies (not generated by Lombok)
	// Simple property getters are auto-generated by @Getter annotation
	public Set<String> getFieldNamesToMask() {
		return new HashSet<>(fieldNamesToMask);
	}
}

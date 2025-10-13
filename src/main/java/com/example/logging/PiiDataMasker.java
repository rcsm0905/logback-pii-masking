package com.example.logging;

import ch.qos.logback.core.spi.ContextAwareBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.*;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * Utility class for masking PII (Personally Identifiable Information) in log messages
 * Uses Jackson ObjectMapper for tree-based traversal to handle unlimited nesting depth
 * 
 * NOTE: Uses Logback's status API (addWarn/addError) instead of logger
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
	private String ocrFields;
	private String ocrMaskToken = "[REDACTED]";
	private boolean maskBase64 = false;
	private int maxMessageSize; // Set via logback-spring.xml (default: 1MB)
	private boolean prettyPrint = false; // Pretty-print output for local dev

	// Parsed field sets for O(1) lookup
	private Set<String> fieldNamesToMask = Collections.emptySet();
	private Set<String> ocrFieldNames = Collections.emptySet();
	
	// Base64 pattern for image masking (still use regex for this)
	private Pattern base64Pattern;

	public void start() {
		try {
			// Parse and validate configuration
			this.fieldNamesToMask = new HashSet<>(parseFieldsList(this.maskedFields));
			this.ocrFieldNames = new HashSet<>(parseFieldsList(this.ocrFields));

			// Validate that at least some masking is configured
			validateConfiguration();

		// Validate maxMessageSize is properly configured
		if (maxMessageSize <= 0) {
			throw new IllegalStateException(
				"maxMessageSize must be configured and > 0. Current value: " + maxMessageSize + 
				". Configure in logback-spring.xml: <maxMessageSize>1000000</maxMessageSize>"
			);
		}

		// Build base64 pattern if needed
		buildBase64Pattern();

		// Use Logback status API to prevent infinite recursion
		addInfo(String.format("PII masking initialized successfully. Masked fields: %d, OCR fields: %d, Base64 masking: %s, Max message size: %dKB", 
			fieldNamesToMask.size(), ocrFieldNames.size(), maskBase64, maxMessageSize / 1024));

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
		if (fieldNamesToMask.isEmpty() && ocrFieldNames.isEmpty() && !maskBase64) {
			throw new IllegalStateException(
					"No masking patterns configured. This could lead to PII data exposure. " +
							"Configure maskedFields, ocrFields, or enable maskBase64."
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

	private void buildBase64Pattern() {
		if (!maskBase64) {
			return;
		}

		List<String> patternParts = new ArrayList<>();

		// Base64 JPEG pattern - limited length to prevent excessive backtracking
		patternParts.add("(/9j/[A-Za-z0-9+/=]{50,100000})");

		// Base64 PNG pattern - limited length to prevent excessive backtracking
		patternParts.add("(iVBORw0KGgo[A-Za-z0-9+/=]{50,100000})");

		String masterPatternString = String.join("|", patternParts);
		this.base64Pattern = Pattern.compile(masterPatternString);
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
			// Use Logback status API to prevent infinite recursion
			addWarn(String.format("Message too large for masking (%dKB), redacting entire log for safety. Limit: %dKB", 
				message.length() / 1024, maxMessageSize / 1024));
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
			String maskedJson = outputMapper.writeValueAsString(rootNode);
			
			// Apply base64 masking if enabled (still use regex for this)
			if (base64Pattern != null) {
				maskedJson = maskBase64Images(maskedJson);
			}
			
			return maskedJson;

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
	 * Inspired by: https://poe.com/s/zUc2aVYZZ0sBoJfW05GH
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
		if (recursionDepth.get() >= MAX_RECURSION_DEPTH) {
			addWarn("Max recursion depth (" + MAX_RECURSION_DEPTH + ") reached, stopping traversal");
			return true;
		}
		return false;
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
			// Log warning with stack trace for troubleshooting
			addWarn("Failed to parse nested JSON in field '" + fieldName + "': " + e.getMessage(), e);
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
		
		return -1; // Not found within search limit
	}
	
	/**
	 * Context holder for iterative traversal
	 * Simplified to only hold the node reference
	 */
	private static class FieldContext {
		final JsonNode node;
		
		FieldContext(JsonNode node) {
			this.node = node;
		}
	}
	
	/**
	 * Determine if a field should be masked
	 */
	private boolean shouldMaskField(String fieldName) {
		// Check regular fields
		if (fieldNamesToMask.contains(fieldName)) {
			return true;
		}
		
		// Check OCR fields (entire object replacement)
		if (ocrFieldNames.contains(fieldName)) {
			return true;
		}
		
		return false;
	}
	
	/**
	 * Mask a JSON value based on field name and type
	 */
	private JsonNode maskValue(String fieldName, JsonNode value) {
		// Special handling for OCR fields - replace entire object
		if (ocrFieldNames.contains(fieldName)) {
			return TextNode.valueOf("{" + ocrMaskToken + "}");
		}
		
		// Regular fields - replace value only
		return TextNode.valueOf(maskToken);
	}
	
	/**
	 * Apply base64 image masking using regex (final pass)
	 */
	private String maskBase64Images(String json) {
		if (base64Pattern == null) {
			return json;
		}
		
		try {
			return base64Pattern.matcher(json).replaceAll("[REDACTED]");
		} catch (Exception e) {
			// SECURITY: Don't return original JSON - could contain PII in base64 strings
			addError("SECURITY ALERT: Failed to mask base64 images. Redacting entire log for safety. Error: " + e.getMessage(), e);
			return "[BASE64 MASKING ERROR - LOG REDACTED FOR SAFETY] Error: " + e.getMessage() + 
				" | Timestamp: " + System.currentTimeMillis();
		}
	}

	// Custom getters for defensive copies (not generated by Lombok)
	// Simple property getters are auto-generated by @Getter annotation
	public Set<String> getFieldNamesToMask() {
		return new HashSet<>(fieldNamesToMask);
	}

	public Set<String> getOcrFieldNames() {
		return new HashSet<>(ocrFieldNames);
	}

	// For testing - check if masking is properly initialized
	public boolean isMaskingInitialized() {
		return !fieldNamesToMask.isEmpty() || !ocrFieldNames.isEmpty() || base64Pattern != null;
	}
}

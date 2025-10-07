package com.example.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;

/**
 * Masks JSON by key name using a stack-based traversal (ArrayDeque), inspired by the provided JsonTraversal.
 * - If a field name matches any of the keys in piiKeys (case-insensitive), its value is replaced with maskText.
 * - Special case: For objects with { "name": "KEY", "value": "..." }, if KEY is in piiKeys, mask "value".
 */
class PiiJsonMasker {
    private final ObjectMapper mapper;

    PiiJsonMasker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String maskJsonObjectString(String json, Set<String> piiKeys, String maskText) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (!root.isObject() && !root.isArray()) {
            return json; // not an object/array, nothing to do
        }
        JsonNode masked = maskInPlace(root, piiKeys, maskText);
        return mapper.writeValueAsString(masked);
    }

    private JsonNode maskInPlace(JsonNode rootNode, Set<String> piiKeys, String maskText) {
        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(rootNode);

        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();

            if (node.isObject()) {
                ObjectNode obj = (ObjectNode) node;

                // Special case: { "name": "KEY", "value": "..." }
                if (obj.has("name") && obj.has("value")) {
                    JsonNode nameNode = obj.get("name");
                    if (nameNode.isTextual()) {
                        String nameUpper = nameNode.asText().toUpperCase(Locale.ROOT);
                        if (piiKeys.contains(nameUpper)) {
                            obj.set("value", TextNode.valueOf(maskText));
                        }
                    }
                }

                // Mask direct children if field name matches PII key
                obj.fields().forEachRemaining(entry -> {
                    String keyUpper = entry.getKey().toUpperCase(Locale.ROOT);
                    JsonNode valueNode = entry.getValue();
                    if (piiKeys.contains(keyUpper)) {
                        // Replace any value with maskText
                        obj.set(entry.getKey(), TextNode.valueOf(maskText));
                    } else {
                        // Continue traversal
                        stack.push(valueNode);
                    }
                });

            } else if (node.isArray()) {
                ArrayNode arr = (ArrayNode) node;
                for (int i = 0; i < arr.size(); i++) {
                    JsonNode child = arr.get(i);
                    stack.push(child);
                }
            } else {
                // Value node: nothing to do
            }
        }
        return rootNode;
    }
}
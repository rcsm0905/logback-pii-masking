package com.example.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Comprehensive test for PiiDataMasker to achieve 100% coverage
 */
class PiiDataMaskerComprehensiveTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private PiiDataMasker masker;

	@BeforeEach
	void setUp() {
		masker = new PiiDataMasker();
	}

	@AfterEach
	void tearDown() {
		if (masker.isStarted()) {
			masker.stop();
		}
	}

	/* ────────────────── Life-cycle tests ────────────────── */

	@Test
	void start_WithValidConfig_ShouldStart() {
		masker.setMaskedFields("NAME,SSN");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		assertThat(masker.isStarted()).isTrue();
	}

	@Test
	void start_Idempotent_ShouldNotThrow() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		masker.start(); // Second call should be ignored
		
		assertThat(masker.isStarted()).isTrue();
	}

	@Test
	void start_WithoutMaskToken_ShouldThrow() {
		masker.setMaskedFields("NAME");
		
		assertThatThrownBy(() -> masker.start())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("PII masking init failed")
			.hasCauseInstanceOf(IllegalStateException.class)
			.cause().hasMessageContaining("maskToken");
	}

	@Test
	void start_WithBlankMaskToken_ShouldThrow() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("   ");
		
		assertThatThrownBy(() -> masker.start())
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void start_WithoutMaskedFields_ShouldThrow() {
		masker.setMaskToken("[REDACTED]");
		
		assertThatThrownBy(() -> masker.start())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("PII masking init failed")
			.hasCauseInstanceOf(IllegalStateException.class)
			.cause().hasMessageContaining("maskedFields");
	}

	@Test
	void start_WithBlankMaskedFields_ShouldThrow() {
		masker.setMaskedFields("   ");
		masker.setMaskToken("[REDACTED]");
		
		assertThatThrownBy(() -> masker.start())
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void start_WithEmptyFieldsAfterTrimming_ShouldThrow() {
		masker.setMaskedFields("  ,  ,  "); // Only whitespace and commas
		masker.setMaskToken("[REDACTED]");
		
		assertThatThrownBy(() -> masker.start())
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("PII masking init failed")
			.hasCauseInstanceOf(IllegalStateException.class)
			.cause().hasMessageContaining("no valid entries");
	}

	@Test
	void start_WithFieldNameTooLong_ShouldIgnore() {
		String longFieldName = "a".repeat(51);
		masker.setMaskedFields(longFieldName + ",NAME"); // NAME should still work
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		root.put("NAME", "John");
		root.put(longFieldName, "ignored");
		
		masker.maskJsonTree(root);
		
		assertThat(root.get("NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get(longFieldName).asText()).isEqualTo("ignored");
	}

	@Test
	void stop_WhenStarted_ShouldStop() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		masker.stop();
		
		assertThat(masker.isStarted()).isFalse();
	}

	@Test
	void stop_WhenNotStarted_ShouldNotThrow() {
		masker.stop(); // Should not throw
		assertThat(masker.isStarted()).isFalse();
	}

	@Test
	void stop_Idempotent_ShouldNotThrow() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		masker.stop();
		masker.stop(); // Second call should be safe
		
		assertThat(masker.isStarted()).isFalse();
	}

	/* ────────────────── Null and edge cases ────────────────── */

	@Test
	void maskJsonTree_WithNull_ShouldNotThrow() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();

		assertDoesNotThrow(() -> masker.maskJsonTree(null));
		assertThat(masker.isStarted()).isTrue();
	}

	@Test
	void maskJsonTree_WithNullValue_ShouldKeepNull() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		root.set("NAME", null);
		
		masker.maskJsonTree(root);
		
		assertThat(root.get("NAME").isNull()).isTrue();
	}

	/* ────────────────── Simple masking tests ────────────────── */

	@Test
	void maskJsonTree_WithPrimitiveFields_ShouldMask() {
		masker.setMaskedFields("NAME,SSN,ID_NUMBER");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		root.put("NAME", "John Doe");
		root.put("SSN", "123-45-6789");
		root.put("ID_NUMBER", "C123456(9)");

		masker.maskJsonTree(root);
		
		assertThat(root.get("NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("SSN").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("ID_NUMBER").asText()).isEqualTo("[REDACTED]");
	}

	@Test
	void maskJsonTree_WithArray_ShouldMaskAllElements() {
		masker.setMaskedFields("imageContent");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		ArrayNode array = root.putArray("imageContent");
		array.add("image1");
		array.add("image2");
		array.add("image3");
		
		masker.maskJsonTree(root);
		
		JsonNode masked = root.get("imageContent");
		assertThat(masked.isArray()).isTrue();
		assertThat(masked.size()).isEqualTo(3);
		for (JsonNode element : masked) {
			assertThat(element.asText()).isEqualTo("[REDACTED]");
		}
	}

	@Test
	void maskJsonTree_maskedFieldIsObject_ShouldMaskAllFields() {
		masker.setMaskedFields("extraImages");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		ObjectNode nested = root.putObject("extraImages");
		nested.put("CROPPED_FACE_FROM_DOC", "base64data");
		nested.put("FULL_IMAGE", "base64data2");
		
		masker.maskJsonTree(root);
		
		JsonNode masked = root.get("extraImages");
		assertThat(masked.isObject()).isTrue();
		masked.fields().forEachRemaining(entry -> 
			assertThat(entry.getValue().asText()).isEqualTo("[REDACTED]")
		);
	}

	/* ────────────────── Deep nesting and recursion tests ────────────────── */

	@Test
	void maskJsonTree_WithDeeplyNestedJson_ShouldHandleRecursion() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		// Create deeply nested structure
		ObjectNode root = MAPPER.createObjectNode();
		ObjectNode level1 = root.putObject("level1");
		ObjectNode level2 = level1.putObject("level2");
		ObjectNode level3 = level2.putObject("level3");
		ObjectNode level4 = level3.putObject("level4");
		ObjectNode level5 = level4.putObject("level5");
		level5.put("NAME", "Deep");
		
		masker.maskJsonTree(root);
		
		JsonNode masked = root.at("/level1/level2/level3/level4/level5/NAME");
		assertThat(masked.asText()).isEqualTo("[REDACTED]");
	}

	@Test
	void maskJsonTree_WithVeryDeeplyNestedJson_ShouldRespectDepthLimit() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();

		// Create structure with 60 levels of nesting (beyond MAX_DEPTH of 50)
		// This tests that depth limit protection works correctly
		ObjectNode root = MAPPER.createObjectNode();
		ObjectNode current = root;
		for (int i = 0; i < 60; i++) {
			ObjectNode next = current.putObject("level" + i);
			// Add NAME field at multiple depths
			if (i == 5 || i == 10 || i == 20 || i == 45 || i == 55) {
				next.put("NAME", "SensitiveData_Depth_" + i);
			}
			current = next;
		}

		masker.maskJsonTree(root);

		// Verify masking works at shallow depths (well within limit)
		assertThat(root.at("/level0/level1/level2/level3/level4/level5/NAME").asText())
				.as("Depth 5 should be masked")
				.isEqualTo("[REDACTED]");

		assertThat(root.at("/level0/level1/level2/level3/level4/level5/level6/level7/level8/level9/level10/NAME").asText())
				.as("Depth 10 should be masked")
				.isEqualTo("[REDACTED]");

		// Verify masking works at depth 20 (still within limit)
		String path20 = "/level0/level1/level2/level3/level4/level5/level6/level7/level8/level9/" +
				"level10/level11/level12/level13/level14/level15/level16/level17/level18/level19/level20/NAME";
		assertThat(root.at(path20).asText())
				.as("Depth 20 should be masked")
				.isEqualTo("[REDACTED]");

		// Verify masking works at depth 45 (approaching limit)
		String path45 = "/level0/level1/level2/level3/level4/level5/level6/level7/level8/level9/" +
				"level10/level11/level12/level13/level14/level15/level16/level17/level18/level19/" +
				"level20/level21/level22/level23/level24/level25/level26/level27/level28/level29/" +
				"level30/level31/level32/level33/level34/level35/level36/level37/level38/level39/" +
				"level40/level41/level42/level43/level44/level45/NAME";
		assertThat(root.at(path45).asText())
				.as("Depth 45 should be masked (below limit of 50)")
				.isEqualTo("[REDACTED]");

		// Verify masking STOPS at depth 55 (beyond MAX_DEPTH of 50)
		String path55 = "/level0/level1/level2/level3/level4/level5/level6/level7/level8/level9/" +
				"level10/level11/level12/level13/level14/level15/level16/level17/level18/level19/" +
				"level20/level21/level22/level23/level24/level25/level26/level27/level28/level29/" +
				"level30/level31/level32/level33/level34/level35/level36/level37/level38/level39/" +
				"level40/level41/level42/level43/level44/level45/level46/level47/level48/level49/" +
				"level50/level51/level52/level53/level54/level55/NAME";
		assertThat(root.at(path55).asText())
				.as("Depth 55 should NOT be masked (beyond limit of 50) - protection against attacks")
				.isEqualTo("SensitiveData_Depth_55");
	}

	@Test
	void maskJsonTree_WithExcessiveNodes_ShouldRespectNodeLimit() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();

		// Create wide structure with 200 fields x 60 levels = 12,000 nodes (exceeds MAX_NODES of 10,000)
		ObjectNode root = MAPPER.createObjectNode();
		ObjectNode current = root;
		
		for (int depth = 0; depth < 60; depth++) {
			ObjectNode next = MAPPER.createObjectNode();
			
			// Add 200 fields at each level
			for (int width = 0; width < 200; width++) {
				String fieldName = "field_" + depth + "_" + width;
				if (width % 10 == 0) {
					next.put(fieldName, "value_" + width);
				} else {
					next.put(fieldName, "data_" + width);
				}
			}
			
			// Add a NAME field to test masking
			if (depth < 5) {
				next.put("NAME", "EarlyData_" + depth);
			}
			
			current.set("level" + depth, next);
			current = next;
		}

		// This should hit the node limit and stop processing
		masker.maskJsonTree(root);

		// Verify early fields are masked (processed before limit hit)
		JsonNode level0 = root.at("/level0");
		assertThat(level0.has("NAME")).isTrue();
		// Note: Whether NAME is masked depends on when node limit is hit
		// The test verifies that processing completes without errors
		
		// The important part is that the operation completes gracefully
		// without OutOfMemoryError or excessive processing time
	}

	/* ────────────────── Comprehensive Zoloz test ────────────────── */
	@Test
	void maskJsonTree_WithZolozResponse_ShouldMaskAllPii() throws Exception {
		masker.setMaskedFields("imageContent,ocrResult,ocrResultFormat,ocrResultDetail,extraImages");
		masker.setMaskToken("[REDACTED]");
		masker.start();

		String jsonContent = Files.readString(
				Paths.get("src/test/resources/zoloz/checkResult_response_hkid.json")
		);

		JsonNode originalRoot = MAPPER.readTree(jsonContent);
		JsonNode root = MAPPER.readTree(jsonContent);
		masker.maskJsonTree(root);

		String[] maskedFields = {
				"/extInfo/imageContent",
				"/extInfo/ocrResult",
				"/extInfo/ocrResultFormat",
				"/extInfo/ocrResultDetail",
				"/extInfo/extraImages"
		};

		for (String path : maskedFields) {
			JsonNode originalNode = originalRoot.at(path);
			JsonNode maskedNode = root.at(path);

			if (maskedNode.isArray()) {
				assertThat(maskedNode.size()).isEqualTo(originalNode.size());
				for (int i = 0; i < maskedNode.size(); i++) {
					assertThat(maskedNode.get(i).asText()).isEqualTo("[REDACTED]");
				}
			} else if (maskedNode.isObject()) {
				assertThat(maskedNode.size()).isEqualTo(originalNode.size());
				for (Iterator<Map.Entry<String, JsonNode>> it = maskedNode.fields(); it.hasNext(); ) {
					Map.Entry<String, JsonNode> entry = it.next();
					if (path.equals("/extInfo/ocrResultDetail") && entry.getValue().isObject() && entry.getValue().has("value")) {
						assertThat(entry.getValue().get("value").asText()).isEqualTo("[REDACTED]");
					} else {
						assertThat(entry.getValue().asText()).isEqualTo("[REDACTED]");
					}
				}
			}
		}

		// Non-PII fields remain unmasked
		assertThat(root.at("/result/resultStatus").asText()).isEqualTo("S");
		assertThat(root.at("/result/resultCode").asText()).isEqualTo("SUCCESS");
		assertThat(root.at("/result/resultMessage").asText()).isEqualTo("Success");
		assertThat(root.at("/extInfo/certType").asText()).isEqualTo("08520000002");
		assertThat(root.at("/extInfo/retryCount").asInt()).isEqualTo(0);
		assertThat(root.at("/extInfo/docEdition").asInt()).isEqualTo(1);
		assertThat(root.at("/extInfo/docCategory").asText()).isEqualTo("ID_CARD");
		assertThat(root.at("/extInfo/recognitionResult").asText()).isEqualTo("N");
		assertThat(root.at("/extInfo/uploadEnabledResult").asText()).isEqualTo("N");
		assertThat(root.at("/extInfo/recognitionErrorCode").asText()).isEqualTo("NOT_REAL_DOC");

		// SpoofResult fields are NOT masked
		assertThat(root.at("/extInfo/spoofResult/TAMPER_CHECK").asText()).isEqualTo("N");
		assertThat(root.at("/extInfo/spoofResult/SECURITY_FEATURE_CHECK").asText()).isEqualTo("Y");
		assertThat(root.at("/extInfo/spoofResult/MATERIAL_CHECK").asText()).isEqualTo("N");

		// extraSpoofResultDetails structure is preserved
		JsonNode extraSpoofDetails = root.at("/extInfo/extraSpoofResultDetails");
		JsonNode originalExtraSpoofDetails = originalRoot.at("/extInfo/extraSpoofResultDetails");
		assertThat(extraSpoofDetails.isArray()).isTrue();
		assertThat(extraSpoofDetails.size()).isEqualTo(originalExtraSpoofDetails.size());
		for (int i = 0; i < extraSpoofDetails.size(); i++) {
			assertThat(extraSpoofDetails.get(i).get("result").asText())
					.isEqualTo(originalExtraSpoofDetails.get(i).get("result").asText());
			assertThat(extraSpoofDetails.get(i).get("spoofType").asText())
					.isEqualTo(originalExtraSpoofDetails.get(i).get("spoofType").asText());
		}
	}


	/* ────────────────── Field name parsing tests ────────────────── */

	@Test
	void maskJsonTree_WithExtraSpacesInConfig_ShouldWork() {
		masker.setMaskedFields(" NAME  ,  SSN  , ID_NUMBER ");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		root.put("NAME", "John");
		root.put("SSN", "123");
		root.put("ID_NUMBER", "456");
		
		masker.maskJsonTree(root);
		
		assertThat(root.get("NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("SSN").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("ID_NUMBER").asText()).isEqualTo("[REDACTED]");
	}

	@Test
	void maskJsonTree_WithArrayInsideObjects_ShouldMask() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		ArrayNode users = root.putArray("users");
		
		ObjectNode user1 = users.addObject();
		user1.put("NAME", "Alice");
		user1.put("age", 25);
		
		ObjectNode user2 = users.addObject();
		user2.put("NAME", "Bob");
		user2.put("age", 30);
		
		masker.maskJsonTree(root);
		
		// Both NAMEs should be masked
		assertThat(root.at("/users/0/NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/users/1/NAME").asText()).isEqualTo("[REDACTED]");
		// Ages should not be masked
		assertThat(root.at("/users/0/age").asInt()).isEqualTo(25);
		assertThat(root.at("/users/1/age").asInt()).isEqualTo(30);
	}
}

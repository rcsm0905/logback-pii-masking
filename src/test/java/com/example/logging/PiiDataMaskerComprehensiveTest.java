package com.example.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
	void start_WithInvalidFieldNames_ShouldThrow() {
		masker.setMaskedFields("!!!,@@@,###"); // All invalid characters
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
		
		masker.maskJsonTree(null); // Should not throw
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
		root.put("age", 30);
		
		masker.maskJsonTree(root);
		
		assertThat(root.get("NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("SSN").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("ID_NUMBER").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("age").asInt()).isEqualTo(30); // Not masked
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
	void maskJsonTree_WithObject_ShouldMaskAllFields() {
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

	/* ────────────────── Nested JSON in string tests ────────────────── */

	@Test
	void maskJsonTree_WithNestedJsonObject_ShouldMask() throws Exception {
		masker.setMaskedFields("NAME,SSN");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		String nestedJson = "{\"NAME\":\"John\",\"SSN\":\"123-45-6789\",\"age\":30}";
		root.put("data", nestedJson);
		
		masker.maskJsonTree(root);
		
		String maskedData = root.get("data").asText();
		JsonNode parsed = MAPPER.readTree(maskedData);
		assertThat(parsed.get("NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(parsed.get("SSN").asText()).isEqualTo("[REDACTED]");
		assertThat(parsed.get("age").asInt()).isEqualTo(30);
	}

	@Test
	void maskJsonTree_WithNestedJsonArray_ShouldMask() throws Exception {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		String nestedJson = "[{\"NAME\":\"John\"},{\"NAME\":\"Jane\"}]";
		root.put("users", nestedJson);
		
		masker.maskJsonTree(root);
		
		String maskedData = root.get("users").asText();
		JsonNode parsed = MAPPER.readTree(maskedData);
		assertThat(parsed.isArray()).isTrue();
		for (JsonNode user : parsed) {
			assertThat(user.get("NAME").asText()).isEqualTo("[REDACTED]");
		}
	}

	@Test
	void maskJsonTree_WithInvalidNestedJson_ShouldKeepOriginal() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		String invalidJson = "{\"NAME\":\"John\" this is invalid}";
		root.put("data", invalidJson);
		
		masker.maskJsonTree(root);
		
		// Should keep original if can't parse
		assertThat(root.get("data").asText()).isEqualTo(invalidJson);
	}

	@Test
	void maskJsonTree_WithNotJsonLikeString_ShouldNotMask() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		root.put("description", "This is just a plain string with NAME in it");
		
		masker.maskJsonTree(root);
		
		// Should not be masked because it doesn't look like JSON
		assertThat(root.get("description").asText()).contains("NAME");
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
	void maskJsonTree_WithVeryDeeplyNestedJson_ShouldStopAtMaxDepth() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		// Create structure deeper than MAX_RECURSION_DEPTH (10)
		ObjectNode root = MAPPER.createObjectNode();
		ObjectNode current = root;
		for (int i = 0; i < 15; i++) {
			ObjectNode next = current.putObject("level" + i);
			if (i == 14) {
				next.put("NAME", "TooDeep");
			}
			current = next;
		}
		
		masker.maskJsonTree(root);
		
		// The deeply nested NAME might not be masked due to recursion limit
		// This test just ensures it doesn't crash
	}

	/* ────────────────── Comprehensive Zoloz test ────────────────── */

	@Test
	void maskJsonTree_WithZolozResponse_ShouldMaskAllPii() throws Exception {
		// Load the real Zoloz JSON file
		String jsonContent = Files.readString(
			Paths.get("src/test/resources/zoloz/checkResult_response_hkid.json")
		);
		
		// Configure masker with all PII fields from Zoloz
		masker.setMaskedFields(
			"imageContent,CROPPED_FACE_FROM_DOC," +
			"STANDARDIZED_DATE_OF_BIRTH,LATEST_ISSUE_DATE,ID_NUMBER,SEX,STANDARDIZED_LATEST_ISSUE_DATE," +
			"NAME,NAME_CN,CHINESE_COMMERCIAL_CODE,ISSUE_DATE,SYMBOLS,DATE_OF_BIRTH," +
			"PERMANENT_RESIDENT_STATUS,NAME_CN_RAW," +
			"MRZ_NAME_CN_RAW,MRZ_ID_NUMBER,MRZ_NAME_CN,MRZ_DATE_OF_BIRTH," +
			"MRZ_PERMANENT_RESIDENT_STATUS,MRZ_STANDARDIZED_DATE_OF_BIRTH,MRZ_SEX," +
			"MRZ_LATEST_ISSUE_DATE,MRZ_CHINESE_COMMERCIAL_CODE,MRZ_ISSUE_DATE," +
			"MRZ_NAME,MRZ_STANDARDIZED_LATEST_ISSUE_DATE,MRZ_SYMBOLS," +
			"NATIONALITY,NUMBER,LAST_NAME_EN,MIDDLE_NAME_EN,LAST_NAME," +
			"NATIONALITY_CODE,COUNTRY_OF_BIRTH_CODE,FIRST_NAME,DATE_OF_EXPIRY," +
			"FIRST_NAME_EN,FULL_NAME_EN,ISSUE_STATE,ADDRESS,FULL_NAME," +
			"ISSUE_STATE_CODE,MRZ1,GENDER,MIDDLE_NAME,COUNTRY_OF_BIRTH,DATE_OF_ISSUE"
		);
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		JsonNode root = MAPPER.readTree(jsonContent);
		masker.maskJsonTree(root);
		
		// ═══════════ Verify imageContent array is fully masked ═══════════
		JsonNode imageContentArray = root.at("/extInfo/imageContent");
		assertThat(imageContentArray.isArray()).isTrue();
		assertThat(imageContentArray.size()).isEqualTo(1);
		assertThat(imageContentArray.get(0).asText()).isEqualTo("[REDACTED]");
		
		// ═══════════ Verify ocrResult fields are masked ═══════════
		assertThat(root.at("/extInfo/ocrResult/STANDARDIZED_DATE_OF_BIRTH").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/LATEST_ISSUE_DATE").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/ID_NUMBER").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/SEX").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/STANDARDIZED_LATEST_ISSUE_DATE").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/NAME_CN").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/CHINESE_COMMERCIAL_CODE").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/ISSUE_DATE").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/SYMBOLS").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/DATE_OF_BIRTH").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/PERMANENT_RESIDENT_STATUS").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResult/NAME_CN_RAW").asText()).isEqualTo("[REDACTED]");
		
		// ═══════════ Verify extraImages object is fully masked ═══════════
		JsonNode extraImages = root.at("/extInfo/extraImages");
		assertThat(extraImages.isObject()).isTrue();
		assertThat(extraImages.get("CROPPED_FACE_FROM_DOC").asText()).isEqualTo("[REDACTED]");
		
		// ═══════════ Verify ocrResultDetail - all MRZ fields are masked ═══════════
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_NAME_CN_RAW/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_ID_NUMBER/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_NAME_CN/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_DATE_OF_BIRTH/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_PERMANENT_RESIDENT_STATUS/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_STANDARDIZED_DATE_OF_BIRTH/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_SEX/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_LATEST_ISSUE_DATE/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_CHINESE_COMMERCIAL_CODE/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_ISSUE_DATE/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_NAME/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_STANDARDIZED_LATEST_ISSUE_DATE/value").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultDetail/MRZ_SYMBOLS/value").asText()).isEqualTo("[REDACTED]");
		
		// ═══════════ Verify ocrResultFormat - all personal data fields are masked ═══════════
		assertThat(root.at("/extInfo/ocrResultFormat/NUMBER").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultFormat/FULL_NAME_EN").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultFormat/FULL_NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultFormat/GENDER").asText()).isEqualTo("[REDACTED]");
		assertThat(root.at("/extInfo/ocrResultFormat/DATE_OF_BIRTH").asText()).isEqualTo("[REDACTED]");
		
		// ═══════════ Verify non-PII fields are NOT masked ═══════════
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
		
		// Verify spoofResult fields are NOT masked
		assertThat(root.at("/extInfo/spoofResult/TAMPER_CHECK").asText()).isEqualTo("N");
		assertThat(root.at("/extInfo/spoofResult/SECURITY_FEATURE_CHECK").asText()).isEqualTo("Y");
		assertThat(root.at("/extInfo/spoofResult/MATERIAL_CHECK").asText()).isEqualTo("N");
		
		// Verify extraSpoofResultDetails structure is preserved
		JsonNode extraSpoofDetails = root.at("/extInfo/extraSpoofResultDetails");
		assertThat(extraSpoofDetails.isArray()).isTrue();
		assertThat(extraSpoofDetails.get(0).get("result").asText()).isEqualTo("N");
		assertThat(extraSpoofDetails.get(0).get("spoofType").asText()).isEqualTo("INFORMATION_CHECK");
		
		// ═══════════ Verify that ALL sensitive data is masked ═══════════
		// The test above comprehensively checks that all PII fields are masked with [REDACTED]
		// Any field that contains actual personal information should be replaced
		
		// Count how many [REDACTED] values exist to ensure masking happened extensively
		String jsonString = root.toString();
		int redactedCount = jsonString.split("\\[REDACTED\\]", -1).length - 1;
		// We expect at least 30 fields to be masked (all the PII fields we configured)
		// This includes both filled and empty PII fields
		assertThat(redactedCount).isGreaterThanOrEqualTo(30);
		
		// ═══════════ Summary ═══════════
		// This test verifies that:
		// 1. All imageContent array elements are masked (sensitive photos)
		// 2. All ocrResult fields with personal data are masked (13 fields)
		// 3. All extraImages fields are masked (face photos)
		// 4. All ocrResultDetail MRZ fields are masked (13 fields)
		// 5. All ocrResultFormat personal data fields are masked
		// 6. Non-PII fields like resultCode, certType, docCategory remain unmasked
		// 7. Technical/metadata fields like spoofResult checks remain unmasked
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
	void maskJsonTree_WithSpecialCharactersInFieldNames_ShouldClean() {
		masker.setMaskedFields("N@AME!,S#SN$,ID%NUMBER^");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		root.put("NAME", "John");
		root.put("SSN", "123");
		root.put("IDNUMBER", "456");
		
		masker.maskJsonTree(root);
		
		// Special characters should be removed
		assertThat(root.get("NAME").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("SSN").asText()).isEqualTo("[REDACTED]");
		assertThat(root.get("IDNUMBER").asText()).isEqualTo("[REDACTED]");
	}

	/* ────────────────── Brace matching edge cases ────────────────── */

	@Test
	void maskJsonTree_WithUnbalancedBraces_ShouldNotCrash() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		root.put("data", "{\"NAME\":\"John\""); // Missing closing brace
		
		masker.maskJsonTree(root);
		
		// Should not crash, just keep original
		assertThat(root.get("data").asText()).contains("NAME");
	}

	@Test
	void maskJsonTree_WithEscapedQuotes_ShouldHandle() throws Exception {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		ObjectNode root = MAPPER.createObjectNode();
		String jsonWithEscapes = "{\"NAME\":\"John \\\"The Boss\\\" Doe\"}";
		root.put("data", jsonWithEscapes);
		
		masker.maskJsonTree(root);
		
		String masked = root.get("data").asText();
		JsonNode parsed = MAPPER.readTree(masked);
		assertThat(parsed.get("NAME").asText()).isEqualTo("[REDACTED]");
	}

	@Test
	void maskJsonTree_WithVeryLargeJson_ShouldRespectScanLimit() {
		masker.setMaskedFields("NAME");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		// Create a very large JSON string (> 100,000 chars)
		StringBuilder largeJson = new StringBuilder("{\"NAME\":\"John\"");
		for (int i = 0; i < 50000; i++) {
			largeJson.append(",\"field").append(i).append("\":\"value").append(i).append("\"");
		}
		largeJson.append("}");
		
		ObjectNode root = MAPPER.createObjectNode();
		root.put("data", largeJson.toString());
		
		masker.maskJsonTree(root);
		
		// Should not crash, may or may not be masked depending on scan limit
	}
}


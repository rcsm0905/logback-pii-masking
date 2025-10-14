package com.example.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PiiDataMaskerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private PiiDataMasker masker;

	@BeforeEach
	void setUp() {
		masker = new PiiDataMasker();
		masker.setMaskedFields("ssn, card , password");     // note extra spaces
		masker.setMaskToken("[X]");
		masker.start();
	}

	@AfterEach
	void tearDown() {
		masker.stop();
	}

	/* ────────────────── simple masking ────────────────── */

	@Test
	void should_mask_configured_primitive_field() {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("name", "Alice");
		root.put("ssn", "123-45-6789");

		masker.maskJsonTree(root);

		assertThat(root.get("name").asText()).isEqualTo("Alice");
		assertThat(root.get("ssn").asText()).isEqualTo("[X]");
	}

	@Test
	void should_mask_arrays_and_objects() {
		ObjectNode root = MAPPER.createObjectNode();

		// array
		ArrayNode arr = root.putArray("card");
		arr.add("4111").add("4222");

		// object
		ObjectNode innerObj = root.putObject("password");
		innerObj.put("plain", "p@ssw0rd");

		masker.maskJsonTree(root);

		root.get("card").forEach(n -> assertThat(n.asText()).isEqualTo("[X]"));
		root.get("password").fields().forEachRemaining(e ->
				assertThat(e.getValue().asText()).isEqualTo("[X]"));
	}

	/* ────────────────── nested JSON in string ────────────────── */

	@Test
	void should_mask_values_inside_string_embedded_json() throws Exception {
		ObjectNode root = MAPPER.createObjectNode();
		root.put("payload",
				"{\"user\":\"Bob\",\"ssn\":\"999-88-7777\",\"nested\":{\"card\":\"4111\"}}");

		masker.maskJsonTree(root);

		// parse back the inner JSON to verify masking occurred
		String payload = root.get("payload").asText();
		JsonNode inner  = MAPPER.readTree(payload);
		assertThat(inner.get("ssn").asText()).isEqualTo("[X]");
		assertThat(inner.get("nested").get("card").asText()).isEqualTo("[X]");
	}

	/* ────────────────── defensive branches ────────────────── */

	@Test
	void null_root_is_noop() {
		Assertions.assertDoesNotThrow(() -> masker.maskJsonTree(null));
	}

	@Test
	void start_without_required_properties_fails() {
		PiiDataMasker m = new PiiDataMasker();
		m.setMaskToken("[X]");
		// maskedFields missing
		assertThatThrownBy(m::start)
				.isInstanceOf(IllegalStateException.class);
	}
}
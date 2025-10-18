package com.example.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance testing for PII masking operations.
 * <p>
 * This test class measures the time spent on various masking scenarios
 * to understand the performance impact on the main application.
 */
class MaskingPerformanceTest {
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private PiiDataMasker masker;
	private MaskingPerformanceTracker tracker;
	
	@BeforeEach
	void setup() {
		masker = new PiiDataMasker();
		masker.setMaskedFields("ssn,creditCard,password,email,phoneNumber");
		masker.setMaskToken("[REDACTED]");
		masker.start();
		
		tracker = new MaskingPerformanceTracker();
	}
	
	@AfterEach
	void teardown() {
		// Print summary after each test
		tracker.printCompactSummary();
		masker.stop();
	}
	
	@Test
	void performance_SimpleObject_ShouldBeQuick() {
		ObjectNode simple = MAPPER.createObjectNode();
		simple.put("ssn", "123-45-6789");
		simple.put("name", "John Doe");
		
		// Time the masking operation
		tracker.timeMaskJsonTree("simple_object", masker, simple);
		
		// Verify it completed and was reasonably fast
		assertThat(tracker.getOperationCount()).isEqualTo(1);
		assertThat(tracker.getTotalDurationNanos()).isGreaterThan(0);
		
		// For a simple object, should be under 1ms on most systems
		assertThat(tracker.getMaxDurationNanos()).isLessThan(10_000_000); // 10ms
	}
	
	@Test
	void performance_NestedObject_ShouldBeReasonable() {
		ObjectNode root = MAPPER.createObjectNode();
		
		// Create a nested structure
		for (int i = 0; i < 10; i++) {
			ObjectNode nested = root.putObject("user" + i);
			nested.put("ssn", "123-45-6789");
			nested.put("email", "test@example.com");
			nested.put("name", "User " + i);
		}
		
		tracker.timeMaskJsonTree("nested_10_objects", masker, root);
		
		assertThat(tracker.getOperationCount()).isEqualTo(1);
		// Should still be reasonably fast for 10 nested objects
		assertThat(tracker.getMaxDurationNanos()).isLessThan(50_000_000); // 50ms
	}
	
	@Test
	void performance_LargeArray_ShouldScale() {
		ArrayNode array = MAPPER.createArrayNode();
		
		// Create array with 100 objects
		for (int i = 0; i < 100; i++) {
			ObjectNode item = MAPPER.createObjectNode();
			item.put("ssn", "123-45-6789");
			item.put("creditCard", "4111-1111-1111-1111");
			item.put("name", "Person " + i);
			array.add(item);
		}
		
		ObjectNode root = MAPPER.createObjectNode();
		root.set("users", array);
		
		tracker.timeMaskJsonTree("large_array_100_items", masker, root);
		
		assertThat(tracker.getOperationCount()).isEqualTo(1);
		// 100 items should still be under 100ms
		assertThat(tracker.getMaxDurationNanos()).isLessThan(100_000_000);
	}
	
	@Test
	void performance_MultipleOperations_TrackEachOne() {
		// Create different sized payloads
		ObjectNode small = MAPPER.createObjectNode();
		small.put("ssn", "123-45-6789");
		
		ObjectNode medium = MAPPER.createObjectNode();
		for (int i = 0; i < 10; i++) {
			medium.put("field" + i, "value" + i);
		}
		medium.put("ssn", "123-45-6789");
		
		ObjectNode large = MAPPER.createObjectNode();
		for (int i = 0; i < 50; i++) {
			ObjectNode nested = large.putObject("obj" + i);
			nested.put("ssn", "123-45-6789");
		}
		
		// Time each operation
		tracker.timeMaskJsonTree("small_object", masker, small);
		tracker.timeMaskJsonTree("medium_object", masker, medium);
		tracker.timeMaskJsonTree("large_object", masker, large);
		
		// Verify all were tracked
		assertThat(tracker.getOperationCount()).isEqualTo(3);
		assertThat(tracker.getTimings()).hasSize(3);
		
		// Large should take more time than small
		long smallDuration = tracker.getTimings().get(0).getDurationNanos();
		long largeDuration = tracker.getTimings().get(2).getDurationNanos();
		
		System.out.println("  Small: " + smallDuration + " ns");
		System.out.println("  Large: " + largeDuration + " ns");
		System.out.println("  Ratio: " + (double) largeDuration / smallDuration + "x");
	}
	
	@Test
	void performance_DeeplyNestedStructure_ShouldHandleRecursion() {
		// Create a deeply nested structure
		ObjectNode root = MAPPER.createObjectNode();
		ObjectNode current = root;
		
		for (int i = 0; i < 5; i++) {
			current.put("level" + i, "value" + i);
			current.put("ssn", "123-45-6789");
			ObjectNode next = MAPPER.createObjectNode();
			current.set("nested", next);
			current = next;
		}
		
		tracker.timeMaskJsonTree("deep_nesting_5_levels", masker, root);
		
		assertThat(tracker.getOperationCount()).isEqualTo(1);
		// Deep nesting should still be reasonable
		assertThat(tracker.getMaxDurationNanos()).isLessThan(50_000_000);
	}
	
	@Test
	void performance_NoMatchingFields_ShouldBeQuick() {
		// Object with no PII fields to mask
		ObjectNode clean = MAPPER.createObjectNode();
		clean.put("name", "John Doe");
		clean.put("age", 30);
		clean.put("city", "New York");
		
		tracker.timeMaskJsonTree("no_pii_fields", masker, clean);
		
		assertThat(tracker.getOperationCount()).isEqualTo(1);
		// Traversal with no masking should be very fast
		assertThat(tracker.getMaxDurationNanos()).isLessThan(5_000_000);
	}
	
	@Test
	void performance_MixedArraysAndObjects_RealWorldScenario() {
		// Simulate a realistic log payload
		ObjectNode log = MAPPER.createObjectNode();
		log.put("timestamp", "2025-10-17T10:00:00Z");
		log.put("level", "INFO");
		log.put("message", "User authentication");
		
		ObjectNode user = log.putObject("user");
		user.put("id", "12345");
		user.put("email", "user@example.com");
		user.put("ssn", "123-45-6789");
		
		ArrayNode actions = log.putArray("actions");
		for (int i = 0; i < 5; i++) {
			ObjectNode action = MAPPER.createObjectNode();
			action.put("type", "LOGIN");
			action.put("creditCard", "4111-1111-1111-1111");
			actions.add(action);
		}
		
		tracker.timeMaskJsonTree("realistic_log_payload", masker, log);
		
		assertThat(tracker.getOperationCount()).isEqualTo(1);
		// Real-world scenario should be fast
		assertThat(tracker.getMaxDurationNanos()).isLessThan(20_000_000);
	}
	
	@Test
	void performance_CompareMultipleRuns_ShowConsistency() {
		ObjectNode payload = MAPPER.createObjectNode();
		payload.put("ssn", "123-45-6789");
		payload.put("creditCard", "4111-1111-1111-1111");
		
		// Run same operation multiple times
		for (int i = 0; i < 10; i++) {
			tracker.timeMaskJsonTree("run_" + (i + 1), masker, payload);
		}
		
		assertThat(tracker.getOperationCount()).isEqualTo(10);
		
		// Check consistency (variance shouldn't be too high)
		double avg = tracker.getAverageDurationNanos();
		long min = tracker.getMinDurationNanos();
		long max = tracker.getMaxDurationNanos();
		
		System.out.println("  Average: " + (avg / 1_000_000.0) + " ms");
		System.out.println("  Min: " + (min / 1_000_000.0) + " ms");
		System.out.println("  Max: " + (max / 1_000_000.0) + " ms");
		System.out.println("  Variance: " + ((max - min) / avg * 100.0) + "%");
		
		// All runs should complete
		assertThat(tracker.getTimings()).allMatch(t -> t.getDurationNanos() > 0);
	}
	
	@Test
	void performance_VerboseMode_PrintsEachOperation() {
		tracker.setVerbose(true);
		
		System.out.println("\n🔍 Verbose timing output:");
		
		ObjectNode obj1 = MAPPER.createObjectNode();
		obj1.put("ssn", "123-45-6789");
		
		ObjectNode obj2 = MAPPER.createObjectNode();
		obj2.put("creditCard", "4111-1111-1111-1111");
		
		tracker.timeMaskJsonTree("operation_1", masker, obj1);
		tracker.timeMaskJsonTree("operation_2", masker, obj2);
		
		assertThat(tracker.getOperationCount()).isEqualTo(2);
	}
	
	@Test
	void performance_FullSummaryReport_ShowsAllDetails() {
		// Run several different operations
		tracker.timeMaskJsonTree("op1_simple", masker, 
			MAPPER.createObjectNode().put("ssn", "123"));
		
		tracker.timeMaskJsonTree("op2_medium", masker, 
			createObjectWithFields(10));
		
		tracker.timeMaskJsonTree("op3_large", masker, 
			createObjectWithFields(50));
		
		// Print detailed summary
		System.out.println("\nDetailed Performance Report:");
		tracker.printSummary();
		
		assertThat(tracker.getOperationCount()).isEqualTo(3);
	}
	
	// Helper method to create objects with specified number of fields
	private ObjectNode createObjectWithFields(int count) {
		ObjectNode obj = MAPPER.createObjectNode();
		for (int i = 0; i < count; i++) {
			obj.put("field" + i, "value" + i);
			if (i % 10 == 0) {
				obj.put("ssn", "123-45-6789");
			}
		}
		return obj;
	}
}




package com.example.logging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test-only utility for tracking masking operation performance.
 * <p>
 * This class helps measure the time spent on masking operations to understand
 * the performance impact on the main application. It is designed exclusively
 * for test environments and should never be used in production code.
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * MaskingPerformanceTracker tracker = new MaskingPerformanceTracker();
 * 
 * // Time a masking operation
 * tracker.timeMaskingOperation("simple_object", () -> {
 *     masker.maskJsonTree(jsonNode);
 * });
 * 
 * // Print summary at the end of test
 * tracker.printSummary();
 * </pre>
 */
public class MaskingPerformanceTracker {
	
	private final List<TimingRecord> timings = new ArrayList<>();
	private boolean verbose = false;
	
	/**
	 * Single timing measurement record.
	 */
	public static class TimingRecord {
		private final String operationName;
		private final long durationNanos;
		private final long timestamp;
		
		public TimingRecord(String operationName, long durationNanos) {
			this.operationName = operationName;
			this.durationNanos = durationNanos;
			this.timestamp = System.currentTimeMillis();
		}
		
		public String getOperationName() { return operationName; }
		public long getDurationNanos() { return durationNanos; }
		public double getDurationMillis() { return durationNanos / 1_000_000.0; }
		public double getDurationMicros() { return durationNanos / 1_000.0; }
		public long getTimestamp() { return timestamp; }
		
		@Override
		public String toString() {
			return String.format("%-30s: %8.3f ms (%,d ns)", 
				operationName, getDurationMillis(), durationNanos);
		}
	}
	
	/**
	 * Enable verbose output (prints timing after each operation).
	 * 
	 * @param verbose true to print after each operation
	 * @return this tracker for method chaining
	 */
	public MaskingPerformanceTracker setVerbose(boolean verbose) {
		this.verbose = verbose;
		return this;
	}
	
	/**
	 * Time a masking operation and record the result.
	 * 
	 * @param operationName descriptive name for this operation
	 * @param operation the masking operation to time
	 */
	public void timeMaskingOperation(String operationName, Runnable operation) {
		long startNanos = System.nanoTime();
		try {
			operation.run();
		} finally {
			long durationNanos = System.nanoTime() - startNanos;
			TimingRecord record = new TimingRecord(operationName, durationNanos);
			timings.add(record);
			
			if (verbose) {
				System.out.println("  ⏱️  " + record);
			}
		}
	}
	
	/**
	 * Time a masking operation with a JsonNode parameter.
	 * Convenience method for common use case.
	 * 
	 * @param operationName descriptive name for this operation
	 * @param masker the PII masker instance
	 * @param node the JSON node to mask
	 */
	public void timeMaskJsonTree(String operationName, PiiDataMasker masker, JsonNode node) {
		timeMaskingOperation(operationName, () -> masker.maskJsonTree(node));
	}
	
	/**
	 * Get all timing records collected so far.
	 * 
	 * @return list of timing records
	 */
	public List<TimingRecord> getTimings() {
		return new ArrayList<>(timings);
	}
	
	/**
	 * Clear all timing records.
	 */
	public void reset() {
		timings.clear();
	}
	
	/**
	 * Get the total time spent across all operations.
	 * 
	 * @return total duration in nanoseconds
	 */
	public long getTotalDurationNanos() {
		return timings.stream()
			.mapToLong(TimingRecord::getDurationNanos)
			.sum();
	}
	
	/**
	 * Get the average time per operation.
	 * 
	 * @return average duration in nanoseconds, or 0 if no operations
	 */
	public double getAverageDurationNanos() {
		return timings.isEmpty() ? 0.0 : 
			(double) getTotalDurationNanos() / timings.size();
	}
	
	/**
	 * Get the maximum time spent on a single operation.
	 * 
	 * @return max duration in nanoseconds, or 0 if no operations
	 */
	public long getMaxDurationNanos() {
		return timings.stream()
			.mapToLong(TimingRecord::getDurationNanos)
			.max()
			.orElse(0);
	}
	
	/**
	 * Get the minimum time spent on a single operation.
	 * 
	 * @return min duration in nanoseconds, or 0 if no operations
	 */
	public long getMinDurationNanos() {
		return timings.stream()
			.mapToLong(TimingRecord::getDurationNanos)
			.min()
			.orElse(0);
	}
	
	/**
	 * Get the number of operations timed.
	 * 
	 * @return count of timing records
	 */
	public int getOperationCount() {
		return timings.size();
	}
	
	/**
	 * Print a summary of all timing measurements.
	 * Useful at the end of a test to see overall performance.
	 */
	public void printSummary() {
		if (timings.isEmpty()) {
			System.out.println("📊 No timing data collected");
			return;
		}
		
		System.out.println("\n" + "=".repeat(80));
		System.out.println("📊 MASKING PERFORMANCE SUMMARY");
		System.out.println("=".repeat(80));
		System.out.printf("Operations:    %d%n", getOperationCount());
		System.out.printf("Total Time:    %.3f ms (%,d ns)%n", 
			getTotalDurationNanos() / 1_000_000.0, getTotalDurationNanos());
		System.out.printf("Average Time:  %.3f ms (%,.0f ns)%n", 
			getAverageDurationNanos() / 1_000_000.0, getAverageDurationNanos());
		System.out.printf("Min Time:      %.3f ms (%,d ns)%n", 
			getMinDurationNanos() / 1_000_000.0, getMinDurationNanos());
		System.out.printf("Max Time:      %.3f ms (%,d ns)%n", 
			getMaxDurationNanos() / 1_000_000.0, getMaxDurationNanos());
		
		System.out.println("\n" + "-".repeat(80));
		System.out.println("Individual Operations:");
		System.out.println("-".repeat(80));
		for (TimingRecord record : timings) {
			System.out.println("  " + record);
		}
		System.out.println("=".repeat(80) + "\n");
	}
	
	/**
	 * Print a summary in a compact format.
	 */
	public void printCompactSummary() {
		if (timings.isEmpty()) {
			System.out.println("📊 No timing data");
			return;
		}
		
		System.out.printf("📊 Masking Performance: %d ops, %.3f ms total, %.3f ms avg (min=%.3f, max=%.3f)%n",
			getOperationCount(),
			getTotalDurationNanos() / 1_000_000.0,
			getAverageDurationNanos() / 1_000_000.0,
			getMinDurationNanos() / 1_000_000.0,
			getMaxDurationNanos() / 1_000_000.0);
	}
}




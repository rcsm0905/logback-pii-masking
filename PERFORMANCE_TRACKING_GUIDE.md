# Performance Tracking Guide

## Overview

This guide shows how to measure the performance impact of PII masking operations in test environments. The `MaskingPerformanceTracker` is a test-only utility that helps you understand timing characteristics without affecting production code.

## Quick Start

### Basic Usage

```java
@Test
void testMaskingPerformance() {
    // Setup masker
    PiiDataMasker masker = new PiiDataMasker();
    masker.setMaskedFields("ssn,creditCard,password");
    masker.setMaskToken("[REDACTED]");
    masker.start();
    
    // Create performance tracker
    MaskingPerformanceTracker tracker = new MaskingPerformanceTracker();
    
    // Time a masking operation
    ObjectNode payload = createTestPayload();
    tracker.timeMaskJsonTree("test_operation", masker, payload);
    
    // Print results
    tracker.printCompactSummary();
    // Output: 📊 Masking Performance: 1 ops, 0.523 ms total, 0.523 ms avg
}
```

### Timing Multiple Operations

```java
@Test
void compareMultipleScenarios() {
    MaskingPerformanceTracker tracker = new MaskingPerformanceTracker();
    
    // Time different payload sizes
    tracker.timeMaskJsonTree("small_10_fields", masker, createSmallPayload());
    tracker.timeMaskJsonTree("medium_50_fields", masker, createMediumPayload());
    tracker.timeMaskJsonTree("large_200_fields", masker, createLargePayload());
    
    // Get detailed summary
    tracker.printSummary();
}
```

### Custom Timing with Lambda

```java
@Test
void timeCustomOperation() {
    MaskingPerformanceTracker tracker = new MaskingPerformanceTracker();
    
    // Time any operation
    tracker.timeMaskingOperation("complex_workflow", () -> {
        // Your masking logic here
        masker.maskJsonTree(payload1);
        masker.maskJsonTree(payload2);
        masker.maskJsonTree(payload3);
    });
    
    System.out.println("Total time: " + tracker.getTotalDurationNanos() / 1_000_000.0 + " ms");
}
```

## API Reference

### MaskingPerformanceTracker

#### Methods

| Method | Description |
|--------|-------------|
| `timeMaskJsonTree(name, masker, node)` | Time a single masking operation |
| `timeMaskingOperation(name, lambda)` | Time any custom operation |
| `setVerbose(true)` | Print timing after each operation |
| `getOperationCount()` | Get number of operations timed |
| `getTotalDurationNanos()` | Get cumulative time (nanoseconds) |
| `getAverageDurationNanos()` | Get average time per operation |
| `getMaxDurationNanos()` | Get slowest operation time |
| `getMinDurationNanos()` | Get fastest operation time |
| `getTimings()` | Get list of all timing records |
| `printSummary()` | Print detailed performance report |
| `printCompactSummary()` | Print one-line summary |
| `reset()` | Clear all timing records |

### TimingRecord

Each timing record contains:
- `operationName` - Descriptive name
- `durationNanos` - Time in nanoseconds
- `durationMillis` - Time in milliseconds (computed)
- `durationMicros` - Time in microseconds (computed)
- `timestamp` - When operation was executed

## Example Output

### Compact Summary
```
📊 Masking Performance: 3 ops, 1.153 ms total, 0.384 ms avg (min=0.107, max=0.921)
```

### Detailed Summary
```
================================================================================
📊 MASKING PERFORMANCE SUMMARY
================================================================================
Operations:    10
Total Time:    5.231 ms (5,231,458 ns)
Average Time:  0.523 ms (523,146 ns)
Min Time:      0.042 ms (42,125 ns)
Max Time:      1.234 ms (1,234,567 ns)

--------------------------------------------------------------------------------
Individual Operations:
--------------------------------------------------------------------------------
  small_10_fields              :    0.042 ms (42,125 ns)
  medium_50_fields             :    0.234 ms (234,567 ns)
  large_200_fields             :    1.234 ms (1,234,567 ns)
  ...
================================================================================
```

## Real-World Examples

See the test classes for complete examples:
- `MaskingPerformanceTest.java` - Comprehensive performance test suite with 10 test scenarios
- `PiiDataMaskerComprehensiveTest.example_TimeMaskingOperation_UsingPerformanceTracker()` - Integration example

## Performance Benchmarks

From our test suite, typical masking times:

| Scenario | Fields | Time |
|----------|--------|------|
| Simple object (1 field) | 5 | ~0.04 ms |
| Nested objects (10 users) | 30 | ~0.2 ms |
| Large array (100 items) | 300 | ~2-5 ms |
| Deep nesting (5 levels) | 25 | ~0.3 ms |

**Note:** Actual performance depends on hardware, JVM, and payload complexity.

## Best Practices

1. **Test Realistic Payloads**: Use actual log structures from your application
2. **Measure Warm-Up Time**: First execution may be slower (JIT compilation)
3. **Run Multiple Times**: Check consistency with multiple runs
4. **Compare Scenarios**: Test edge cases (empty, small, large, deeply nested)
5. **Set Thresholds**: Use assertions to catch performance regressions

```java
// Example: Assert performance threshold
@Test
void maskingPerformance_ShouldMeetSLA() {
    MaskingPerformanceTracker tracker = new MaskingPerformanceTracker();
    tracker.timeMaskJsonTree("critical_path", masker, realisticPayload);
    
    // Assert SLA: masking must complete in < 10ms
    assertThat(tracker.getMaxDurationNanos())
        .isLessThan(10_000_000); // 10ms in nanoseconds
}
```

## Integration with JUnit

### AfterEach Hook
```java
@AfterEach
void printPerformance() {
    tracker.printCompactSummary();
}
```

### Verbose Mode for Debugging
```java
@Test
void debugSlowMasking() {
    tracker.setVerbose(true); // Prints after each operation
    
    tracker.timeMaskJsonTree("op1", masker, payload1);
    tracker.timeMaskJsonTree("op2", masker, payload2);
    tracker.timeMaskJsonTree("op3", masker, payload3);
}
```

## Key Design Principles

✅ **Test-Only**: No production overhead - tracker exists only in test code  
✅ **Non-Invasive**: No changes to production `PiiDataMasker` class  
✅ **Simple API**: Easy to add to existing tests  
✅ **Comprehensive Metrics**: Nanosecond precision with min/max/avg  
✅ **Readable Output**: Clear summaries for understanding impact  

## FAQ

**Q: Does this affect production performance?**  
A: No. `MaskingPerformanceTracker` is in the test source tree only and is never used in production code.

**Q: How accurate are the measurements?**  
A: Measurements use `System.nanoTime()` which provides nanosecond precision (though not necessarily nanosecond accuracy on all platforms).

**Q: Can I use this in CI/CD?**  
A: Yes! Use assertions to fail builds if performance degrades:
```java
assertThat(tracker.getMaxDurationNanos()).isLessThan(THRESHOLD);
```

**Q: What's the overhead of the tracker itself?**  
A: Minimal - just timing calls and ArrayList operations. The masking operation itself dominates the time.

---

For more examples, see:
- `/src/test/java/com/example/logging/MaskingPerformanceTest.java`
- `/src/test/java/com/example/logging/PiiDataMaskerComprehensiveTest.java`




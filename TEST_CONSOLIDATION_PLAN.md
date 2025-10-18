# Test Consolidation Plan

## Current Test Files

| File | Tests | Purpose | Status |
|------|-------|---------|--------|
| PiiDataMaskerTest.java | 4 | Basic PiiDataMasker tests | ❌ DELETE (redundant) |
| PiiDataMaskerComprehensiveTest.java | 22 | Comprehensive PiiDataMasker tests | ✅ KEEP |
| JsonStructuredLayoutTest.java | 6 | Exhaustive JsonStructuredLayout tests | ❌ DELETE (redundant) |
| JsonStructuredLayoutComprehensiveTest.java | 30 | Comprehensive JsonStructuredLayout tests | ✅ KEEP |
| MaskingPerformanceTest.java | 10 | Performance testing | ✅ KEEP |
| ZolozResponseMaskingTest.java | 5 | Real-world Zoloz scenarios | ✅ KEEP |
| DepthLimitRiskTest.java | 4 | Depth limit risk demonstrations | ❌ DELETE (covered by Comprehensive) |
| LargeArgumentProtectionTest.java | 7 | Size limit protection | ✅ KEEP |

**Total:** 88 tests → 74 tests after consolidation

---

## Redundancy Analysis

### 1. PiiDataMaskerTest.java → REDUNDANT

| Test in PiiDataMaskerTest | Covered By (in Comprehensive) |
|---------------------------|-------------------------------|
| `should_mask_configured_primitive_field` | `maskJsonTree_WithPrimitiveFields_ShouldMask` |
| `should_mask_arrays_and_objects` | `maskJsonTree_WithArray_ShouldMaskAllElements` + `maskJsonTree_maskedFieldIsObject_ShouldMaskAllFields` |
| `null_root_is_noop` | `maskJsonTree_WithNull_ShouldNotThrow` |
| `start_without_required_properties_fails` | `start_WithoutMaskToken_ShouldThrow` + `start_WithEmptyFieldsAfterTrimming_ShouldThrow` |

**Verdict:** All 4 tests have equivalent or better coverage in PiiDataMaskerComprehensiveTest.

---

### 2. JsonStructuredLayoutTest.java → REDUNDANT

(Note: File is misnamed - class is actually `JsonStructuredLayoutExhaustiveTest`)

| Test in JsonStructuredLayoutTest | Covered By (in Comprehensive) |
|----------------------------------|-------------------------------|
| `nullMdcAndNoThrowable_WhenDoLayout_ThenJsonProduced` | `doLayout_WithNullMDC_ShouldNotCrash` |
| `simpleStringArg_WhenFormatMessage_ThenLeftUntouched` | `formatMessage_WithSimpleArguments_ShouldNotModify` |
| `noArguments_WhenFormatMessage_ThenMessageReturnedAsIs` | `formatMessage_WithNoArguments_ShouldReturnFormattedMessage` |
| `primitiveStringAndEnum_WhenIsSimpleType_ThenCorrectResult` | `isComplexObject_WithEnum_ShouldReturnFalse` + other isComplexObject tests |
| `throwableProxyWithoutFrames_WhenFormatException_ThenSingleLine` | `doLayout_WithExceptionWithoutStackTrace_ShouldHandleGracefully` |
| `throwableInsideLayout_WhenFormatFallback_ThenMinimalOneLiner` | `formatFallback_ShouldProduceErrorLine` |

**Verdict:** All 6 tests have equivalent or better coverage in JsonStructuredLayoutComprehensiveTest.

---

### 3. DepthLimitRiskTest.java → REDUNDANT

| Test in DepthLimitRiskTest | Covered By |
|----------------------------|------------|
| `testExtremeDepth_100Levels_ShowsPerformanceImpact` | `maskJsonTree_WithVeryDeeplyNestedJson_ShouldRespectDepthLimit` (PiiDataMaskerComprehensiveTest) |
| `testVeryWideStructure_10000Fields_ShowsPerformanceImpact` | `maskJsonTree_WithExcessiveNodes_ShouldRespectNodeLimit` (PiiDataMaskerComprehensiveTest) |
| `testCombinedAttack_WideAndDeep_ShowsCompoundedImpact` | Covered by node limit test |
| `testPerformanceComparison_SmallVsLarge` | Covered by MaskingPerformanceTest |

**Verdict:** These tests were demonstrations of risks. The actual protection limits are thoroughly tested in PiiDataMaskerComprehensiveTest. The "risk demonstration" aspect is now documented in markdown files.

---

## Files to Keep

### ✅ PiiDataMaskerComprehensiveTest.java (22 tests)
- Comprehensive coverage of PiiDataMasker
- Tests all edge cases, depth limits, node limits
- Well-organized with clear test names

### ✅ JsonStructuredLayoutComprehensiveTest.java (30 tests)
- Comprehensive coverage of JsonStructuredLayout
- Tests all integration points with PiiDataMasker
- Covers MDC, exceptions, formatting

### ✅ MaskingPerformanceTest.java (10 tests)
- Unique performance benchmarking tests
- Measures masking overhead
- Uses MaskingPerformanceTracker utility

### ✅ ZolozResponseMaskingTest.java (5 tests)
- Real-world integration tests
- Tests actual Zoloz API response format
- Performance comparison between JSON string vs DTO

### ✅ LargeArgumentProtectionTest.java (7 tests)
- Unique tests for size limit protection (Layer 1)
- Tests 500KB size guard
- Performance tests for size checking

---

## Action Plan

1. ❌ **DELETE** `PiiDataMaskerTest.java`
   - All functionality covered by PiiDataMaskerComprehensiveTest

2. ❌ **DELETE** `JsonStructuredLayoutTest.java`
   - All functionality covered by JsonStructuredLayoutComprehensiveTest

3. ❌ **DELETE** `DepthLimitRiskTest.java`
   - Protection limits tested in PiiDataMaskerComprehensiveTest
   - Risk analysis documented in DEPTH_LIMIT_RISK_ANALYSIS.md

---

## Result After Consolidation

| Test Suite | Tests | Purpose |
|------------|-------|---------|
| **PiiDataMaskerComprehensiveTest** | 22 | Complete PiiDataMasker coverage |
| **JsonStructuredLayoutComprehensiveTest** | 30 | Complete JsonStructuredLayout coverage |
| **MaskingPerformanceTest** | 10 | Performance benchmarking |
| **ZolozResponseMaskingTest** | 5 | Real-world scenarios |
| **LargeArgumentProtectionTest** | 7 | Size limit protection |
| **Total** | **74** | **Clean, non-redundant suite** |

---

## Benefits of Consolidation

1. **Reduced Maintenance:** 74 tests instead of 88 (16% reduction)
2. **No Duplication:** Each test verifies unique functionality
3. **Better Organization:** Comprehensive tests in comprehensive files
4. **Faster CI/CD:** Fewer tests to run
5. **Clearer Intent:** No confusion about which test file to update

---

## Verification

Run tests after deletion to ensure coverage is maintained:
```bash
mvn test
mvn jacoco:report
```

Expected: All 74 tests should pass, coverage should remain > 90%



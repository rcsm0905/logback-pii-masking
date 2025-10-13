# CRITICAL FIX: Infinite Recursion Prevention

## Issue Discovered

**Vulnerability:** Using `slf4j.Logger` within `ResilientMaskingPatternLayout` causes infinite recursion

### The Recursion Loop:

```
1. maskSensitiveDataOptimized() is called
        ↓
2. Calls logger.warn("Message too large...")
        ↓
3. Logger creates ILoggingEvent
        ↓
4. Event sent to JsonStructuredLayout
        ↓
5. JsonStructuredLayout calls maskSensitiveDataOptimized()
        ↓
6. Back to step 1
        ↓
INFINITE RECURSION → StackOverflowError! 💥
```

---

## Before Fix (CRITICAL BUG):

**File:** `ResilientMaskingPatternLayout.java`

```java
private static final Logger logger = LoggerFactory.getLogger(ResilientMaskingPatternLayout.class);

public String maskSensitiveDataOptimized(String message) {
    if (message.length() > maxMessageSize) {
        logger.warn("Message too large...");  // ← Triggers infinite recursion!
        return "[REDACTED]";
    }
    
    try {
        // masking logic...
    } catch (Exception e) {
        logger.error("Masking failed", e);  // ← Triggers infinite recursion!
        return "[REDACTED]";
    }
}
```

### Every Logger Call is Dangerous:

1. ✅ Line 59: `logger.info("PII masking initialized...")` - **SAFE** (called during startup before appenders active)
2. 🔴 Line 194: `logger.warn("Message too large...")` - **INFINITE RECURSION**
3. 🔴 Line 223: `logger.error("SECURITY ALERT: PII masking failed...")` - **INFINITE RECURSION**
4. 🔴 Line 242: `logger.warn("Failed to mask individual match...")` - **INFINITE RECURSION**
5. 🔴 Line 251: `logger.error("Failed to apply masking pattern...")` - **INFINITE RECURSION**

---

## Fix Applied: Use Logback Status API

### What is Logback Status API?

Logback has a **separate internal logging system** for framework components:
- `addInfo(String msg)` - Info level
- `addWarn(String msg)` - Warning level
- `addError(String msg, Throwable ex)` - Error level

**Key difference:** These go to Logback's **status system**, NOT through appenders!

### Status Messages Go To:
1. Console (during startup)
2. JMX beans (for monitoring)
3. Status listener (if configured)
4. **NOT** through `JsonStructuredLayout` → **NO RECURSION!**

---

## Changes Made

### 1. Removed SLF4J Logger

**Before:**
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResilientMaskingPatternLayout extends ContextAwareBase {
    private static final Logger logger = LoggerFactory.getLogger(ResilientMaskingPatternLayout.class);
```

**After:**
```java
// No SLF4J imports needed!

/**
 * NOTE: Uses Logback's status API (addWarn/addError) instead of logger
 * to prevent infinite recursion (masking logs would trigger more masking)
 */
public class ResilientMaskingPatternLayout extends ContextAwareBase {
```

### 2. Replaced All Logger Calls

**Startup (line 59):**
```java
// Before:
logger.info("PII masking initialized successfully. Masked fields: {}, OCR fields: {}, Base64 masking: {}", 
    fieldNames.size(), ocrFieldNames.size(), maskBase64);

// After:
addInfo(String.format("PII masking initialized successfully. Masked fields: %d, OCR fields: %d, Base64 masking: %s", 
    fieldNames.size(), ocrFieldNames.size(), maskBase64));
```

**Initialization Error (line 65):**
```java
// Before:
logger.error("CRITICAL: Failed to initialize PII masking patterns. Application cannot start safely.", e);

// After:
addError("CRITICAL: Failed to initialize PII masking patterns. Application cannot start safely.", e);
```

**Oversized Message (line 195):**
```java
// Before:
logger.warn("Message too large for masking ({}KB), redacting entire log for safety. Limit: {}KB", 
    message.length() / 1024, maxMessageSize / 1024);

// After:
addWarn(String.format("Message too large for masking (%dKB), redacting entire log for safety. Limit: %dKB", 
    message.length() / 1024, maxMessageSize / 1024));
```

**Masking Runtime Error (line 224):**
```java
// Before:
logger.error("SECURITY ALERT: PII masking failed. Log entry REDACTED for safety. Error: {}", 
    e.getMessage(), e);

// After:
addError("SECURITY ALERT: PII masking failed. Log entry REDACTED for safety. Error: " + e.getMessage(), e);
```

**Pattern Match Failure (line 242):**
```java
// Before:
logger.warn("Failed to mask individual match, keeping original match. Pattern: {}, Error: {}", 
    pattern.pattern().substring(0, Math.min(50, pattern.pattern().length())), 
    e.getMessage());

// After:
addWarn(String.format("Failed to mask individual match, keeping original match. Pattern: %s, Error: %s", 
    pattern.pattern().substring(0, Math.min(50, pattern.pattern().length())), 
    e.getMessage()));
```

**Pattern Application Failure (line 251):**
```java
// Before:
logger.error("Failed to apply masking pattern. Pattern: {}, Error: {}", 
    pattern.pattern().substring(0, Math.min(50, pattern.pattern().length())), 
    e.getMessage());

// After:
addError(String.format("Failed to apply masking pattern. Pattern: %s, Error: %s", 
    pattern.pattern().substring(0, Math.min(50, pattern.pattern().length())), 
    e.getMessage()));
```

---

## How to View Logback Status Messages

### 1. Console Output (Automatic during startup):
```
15:30:01,123 |-INFO PII masking initialized successfully. Masked fields: 35, OCR fields: 1, Base64 masking: true
```

### 2. Programmatically:
```java
LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
StatusManager sm = lc.getStatusManager();
for (Status status : sm.getCopyOfStatusList()) {
    System.out.println(status.getMessage());
}
```

### 3. Via JMX:
- JMX bean: `ch.qos.logback.classic:Name=default,Type=LoggerContext`
- Attribute: `StatusList`

### 4. Status Listener (add to logback-spring.xml):
```xml
<configuration>
  <statusListener class="ch.qos.logback.core.status.OnConsoleStatusListener" />
  <!-- ... rest of config ... -->
</configuration>
```

---

## Testing

### Test Case 1: Normal Operation ✅

```bash
mvn spring-boot:run
# Check console for status messages during startup
```

**Expected console output:**
```
15:30:01,123 |-INFO PII masking initialized successfully. Masked fields: 35, OCR fields: 1, Base64 masking: true
```

### Test Case 2: Oversized Message 🔴

```bash
# Create a 2MB test message
curl -X POST http://localhost:8080/api/demo/zoloz/check-result \
  -H "Content-Type: application/json" \
  -d @huge_2mb_file.json
```

**Expected:**
- ✅ No StackOverflowError
- ✅ Status message: "Message too large for masking (2048KB)..."
- ✅ Response still works
- ✅ Log shows: "[LOG TOO LARGE - REDACTED FOR SAFETY]"

### Test Case 3: Force Masking Error 🔴

**Temporarily break a pattern:**
```xml
<maskedFields>INVALID[[[PATTERN</maskedFields>
```

**Expected:**
- ✅ No StackOverflowError
- ✅ Application fails to start with clear error
- ✅ Status shows: "CRITICAL: Failed to initialize PII masking patterns"

---

## Performance Impact

### Before Fix:
- **Infinite recursion** → StackOverflowError
- Application crashes

### After Fix:
- ✅ Zero performance overhead
- ✅ Status messages are lightweight (don't go through appenders)
- ✅ No JSON serialization for internal logs
- ✅ No masking applied to status messages

---

## Why This is Better Than ThreadLocal

**Alternative solution:** Use ThreadLocal to detect recursion

```java
private static final ThreadLocal<Boolean> IN_MASKING = ThreadLocal.withInitial(() -> false);

public String maskSensitiveDataOptimized(String message) {
    if (IN_MASKING.get()) {
        return message; // Already masking, avoid recursion
    }
    
    IN_MASKING.set(true);
    try {
        // masking logic...
        logger.warn("Some issue");  // This would be unmasked!
    } finally {
        IN_MASKING.set(false);
    }
}
```

**Problems with ThreadLocal approach:**
- ❌ More complex code
- ❌ ThreadLocal overhead
- ❌ Internal logs would be **unmasked** (could leak PII!)
- ❌ Memory leak risk if not cleaned up properly

**Logback Status API:**
- ✅ Simple
- ✅ Zero overhead
- ✅ Designed for this exact use case
- ✅ No PII exposure risk

---

## Summary

### What Changed:
- ✅ Removed SLF4J Logger dependency
- ✅ Use Logback Status API (`addWarn`, `addError`, `addInfo`)
- ✅ All internal logging goes to status system

### Why:
- 🛡️ Prevents infinite recursion → StackOverflowError
- 🛡️ Status messages don't trigger masking
- 🛡️ Cleaner separation: status vs application logs

### Security Impact:
- ✅ No new vulnerabilities
- ✅ Internal errors still logged (to status system)
- ✅ Stack traces still captured
- ✅ Troubleshooting still possible

### Before:
**Risk:** 🔴 Infinite recursion → Application crash

### After:
**Risk:** ✅ None - Safe internal logging

---

## Lesson Learned

**When writing custom Logback components:**
- ❌ **DON'T** use `slf4j.Logger` for internal logging
- ✅ **DO** use `ContextAwareBase.addWarn/addError/addInfo`

**This applies to:**
- Custom Layouts
- Custom Appenders  
- Custom Filters
- Custom Encoders

**All inherit from `ContextAwareBase` and should use the status API!**




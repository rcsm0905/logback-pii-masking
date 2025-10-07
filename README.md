# Logback PII Data Masking Demo

A Spring Boot application demonstrating PII (Personally Identifiable Information) data masking in log messages using environment variables and custom Logback layout.

## Overview

This application implements a custom Logback layout that automatically masks sensitive data in log messages by:

1. **Detecting JSON objects** within log messages
2. **Parsing JSON content** using Jackson
3. **Masking specified PII fields** by key name (case-insensitive)
4. **Preserving original log formatting** while only altering the message content
5. **Environment variable configuration** for production-ready deployment

## Features

- **Custom Logback Layout**: `PiiMaskingLayout` that intercepts log messages
- **Environment Variable Configuration**: No code changes needed for PII key updates
- **Stack-based JSON Traversal**: Uses `ArrayDeque` for efficient JSON processing
- **Configurable PII Keys**: Comma-separated list via environment variables
- **Special Case Handling**: Supports `{"name": "KEY", "value": "..."}` structures
- **Multiple Appenders**: Console and file logging with PII masking
- **Spring Boot Integration**: RESTful API endpoints for testing
- **Production Ready**: 12-Factor App compliant with environment-based configuration

## Technology Stack

- **Java 17**
- **Spring Boot 3.3.5** (latest stable)
- **Logback** (default Spring Boot logging)
- **Jackson** (JSON processing)
- **Maven** (build tool)

## Project Structure

```
src/main/java/com/example/
├── LogbackMaskingApplication.java          # Main Spring Boot application
├── controller/
│   └── DemoController.java                 # REST endpoints for testing
├── dto/
│   ├── ZolozResponse.java                  # Sample DTO with PII fields
│   ├── OcrResultDetail.java                # OCR result detail structure
│   └── UserProfile.java                    # User profile with PII data
├── logging/
│   ├── PiiMaskingLayout.java               # Custom Logback layout
│   ├── MaskedLoggingEvent.java             # Event wrapper for masking
│   └── PiiJsonMasker.java                  # JSON masking logic
└── service/
    └── DemoService.java                    # Service for testing scenarios
```

## Configuration

### Environment Variables

The application uses environment variables for configuration, making it production-ready and 12-Factor App compliant:

```bash
# PII keys to mask (comma-separated)
export PII_MASK_KEYS="NAME,SSN,EMAIL,CREDIT_CARD,PHONE_NUMBER"

# Mask text to use
export PII_MASK_TEXT="[MASKED]"
```

### Logback Configuration (`logback-spring.xml`)

The application uses a custom layout configured with environment variables:

```xml
<layout class="com.example.logging.PiiMaskingLayout">
    <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{0}: %msg%n</pattern>
    <maskKeys>${PII_MASK_KEYS:-NAME,ID_NUMBER,SSN,EMAIL,CREDIT_CARD_NUMBER,PHONE_NUMBER}</maskKeys>
    <maskText>${PII_MASK_TEXT:-****}</maskText>
</layout>
```

### Default PII Keys

The following keys are configured by default (case-insensitive):

- `NAME`, `ID_NUMBER`, `SSN`, `EMAIL`
- `CREDIT_CARD_NUMBER`, `PHONE_NUMBER`

**Customize via environment variables without code changes!**

## Running the Application

### Prerequisites

- Java 17 or higher
- Maven 3.6 or higher

### Basic Run (Default Configuration)

```bash
# Clone or navigate to the project directory
cd logback-masking

# Build the application
mvn clean compile

# Run with default PII keys
mvn spring-boot:run
```

### Run with Custom Environment Variables

```bash
# Set custom PII keys and mask text
export PII_MASK_KEYS="NAME,SSN,EMAIL,CREDIT_CARD"
export PII_MASK_TEXT="[MASKED]"

# Run the application
mvn spring-boot:run
```

### Production Deployment Examples

**Docker:**
```dockerfile
ENV PII_MASK_KEYS="NAME,SSN,EMAIL,CREDIT_CARD,PHONE_NUMBER"
ENV PII_MASK_TEXT="[MASKED]"
```

**Kubernetes:**
```yaml
env:
- name: PII_MASK_KEYS
  value: "NAME,SSN,EMAIL,CREDIT_CARD"
- name: PII_MASK_TEXT
  value: "[MASKED]"
```

**System Environment:**
```bash
# Set environment variables
export PII_MASK_KEYS="NAME,SSN,EMAIL,CREDIT_CARD,PHONE_NUMBER"
export PII_MASK_TEXT="[MASKED]"

# Run JAR
java -jar target/logback-masking-0.0.1-SNAPSHOT.jar
```

The application will start on `http://localhost:8080`

## Testing the PII Masking

### Available Endpoints

1. **Zoloz eKYC Response Test**
   ```
   GET http://localhost:8080/api/demo/zoloz
   ```

2. **User Profile Test**
   ```
   GET http://localhost:8080/api/demo/user-profile
   ```

3. **Simple JSON Test**
   ```
   GET http://localhost:8080/api/demo/simple-json
   ```

4. **Non-PII Data Test**
   ```
   GET http://localhost:8080/api/demo/non-pii
   ```

5. **Error with PII Test**
   ```
   GET http://localhost:8080/api/demo/error-with-pii
   ```

6. **Run All Tests**
   ```
   GET http://localhost:8080/api/demo/all
   ```

7. **Environment Configuration Check**
   ```
   GET http://localhost:8080/api/demo/env-config
   ```

### Example Log Output

**Before Masking:**
```
2024-01-15 10:30:45.123 INFO  [http-nio-8080-exec-1] DemoService: Received ekyc response from zoloz: {"NAME":"John Doe","ID_NUMBER":"1234567890","DATE_OF_BIRTH":"1990-01-15",...}
```

**After Masking (Default):**
```
2024-01-15 10:30:45.123 INFO  [http-nio-8080-exec-1] DemoService: Received ekyc response from zoloz: {"NAME":"****","ID_NUMBER":"****","DATE_OF_BIRTH":"****",...}
```

**After Masking (Custom Environment Variables):**
```
2024-01-15 10:30:45.123 INFO  [http-nio-8080-exec-1] DemoService: Received ekyc response from zoloz: {"NAME":"[MASKED]","ID_NUMBER":"[MASKED]","DATE_OF_BIRTH":"[MASKED]",...}
```

## How It Works

### 1. Log Message Interception

The `PiiMaskingLayout` intercepts log events before they are formatted and written to output.

### 2. JSON Detection

The layout searches for balanced JSON objects `{...}` within the formatted message using a robust parser that handles:
- Nested objects and arrays
- Escaped quotes in strings
- Complex JSON structures

### 3. PII Masking

The `PiiJsonMasker` uses a stack-based traversal (`ArrayDeque`) to:
- Process JSON nodes iteratively
- Match field names against PII keys from environment variables (case-insensitive)
- Replace matching values with the mask text from environment variables
- Handle special cases like `{"name": "KEY", "value": "..."}` structures

### 4. Message Reconstruction

The masked JSON is reconstructed into the original message format, preserving all non-JSON content.

## Customization

### Adding New PII Keys

Set the `PII_MASK_KEYS` environment variable with comma-separated keys:

```bash
# Add new PII fields
export PII_MASK_KEYS="NAME,SSN,EMAIL,CREDIT_CARD,PHONE_NUMBER,NEW_PII_FIELD_1,NEW_PII_FIELD_2"
```

### Changing Mask Text

Set the `PII_MASK_TEXT` environment variable:

```bash
# Use custom mask text
export PII_MASK_TEXT="[MASKED]"
```

### Custom Log Patterns

Edit `logback-spring.xml` to adjust the log pattern:

```xml
<pattern>%d{ISO8601} [%thread] %-5level %logger{36} - %msg%n</pattern>
```

### Production Benefits

✅ **No Code Changes**: Update PII keys via environment variables  
✅ **No Restart Required**: Set environment variables before starting  
✅ **Environment-Specific**: Different keys per environment  
✅ **Container-Friendly**: Perfect for Docker/Kubernetes  
✅ **12-Factor App Compliant**: Configuration via environment  

## Log Files

- **Console Output**: Real-time logs with PII masking
- **File Output**: `logs/application.log` with daily rotation
- **Log Rotation**: 30 days retention, 10MB max file size

## Security Considerations

- **Performance**: JSON parsing adds overhead; consider performance impact in high-throughput applications
- **Memory**: Stack-based traversal is memory efficient for deep JSON structures
- **Fallback**: If JSON parsing fails, original message is logged (fail-safe behavior)
- **Configuration**: PII keys are case-insensitive and configurable via environment variables
- **Production Ready**: Environment variable configuration is secure and deployment-friendly

## Troubleshooting

### Common Issues

1. **PII Not Masked**: Check that field names match `PII_MASK_KEYS` environment variable (case-insensitive)
2. **Environment Variables Not Working**: Ensure variables are set before starting the application
3. **JSON Parse Errors**: Verify JSON structure in log messages
4. **Performance Issues**: Consider reducing JSON complexity or optimizing PII key list

### Debug Logging

Enable debug logging to see layout behavior:

```properties
logging.level.com.example.logging=DEBUG
```

### Environment Variable Debugging

Check current environment variable configuration:

```bash
# Check environment variables
echo "PII_MASK_KEYS: $PII_MASK_KEYS"
echo "PII_MASK_TEXT: $PII_MASK_TEXT"

# Or use the API endpoint
curl http://localhost:8080/api/demo/env-config
```

## License

This project is for demonstration purposes. Use in production at your own risk.

## Contributing

Feel free to submit issues and enhancement requests!
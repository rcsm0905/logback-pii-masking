package com.example.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.example.dto.ZolozCheckResultResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Integration test for Zoloz response masking using the full JsonStructuredLayout flow.
 * <p>
 * Tests two scenarios:
 * 1. JSON string masking - log a JSON string directly
 * 2. DTO masking - log a deserialized ZolozCheckResultResponse object
 */
class ZolozResponseMaskingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private JsonStructuredLayout layout;
    private PiiDataMasker masker;
    private LoggerContext loggerContext;
    private MaskingPerformanceTracker tracker;

    @BeforeEach
    void setUp() {
        // Setup logger context
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        
        // Setup masker with ID_NUMBER and other PII fields to mask
        masker = new PiiDataMasker();
        masker.setMaskedFields("imageContent,ocrResult,ocrResultFormat,ocrResultDetail,CROPPED_FACE_FROM_DOC");
        masker.setMaskToken("[REDACTED]");
        masker.start();

        // Setup layout
        layout = new JsonStructuredLayout();
        layout.setMaskingLayout(masker);
        layout.setPrettyPrint(false);
        layout.start();
        
        // Setup performance tracker
        tracker = new MaskingPerformanceTracker();
    }

    @AfterEach
    void tearDown() {
        tracker.printCompactSummary();
        
        if (layout != null && layout.isStarted()) {
            layout.stop();
        }
        if (masker != null && masker.isStarted()) {
            masker.stop();
        }
    }

    /**
     * Test Case 1: JSON String Masking
     * <p>
     * Reads the Zoloz response JSON file as a string and logs it.
     * Tests that ID_NUMBER and other PII fields are properly masked when logging JSON strings.
     */
    @Test
    void testJsonStringMasking_ZolozResponse() throws Exception {
        // Read JSON file as string
        String jsonPath = "src/test/resources/zoloz/checkResult_response_hkid.json";
        String jsonString = Files.readString(Paths.get(jsonPath));
        
        // Create logging event with JSON string as argument
        ILoggingEvent event = createLoggingEvent(
            "Receive Zoloz Check Result Response: {}", 
            new Object[]{jsonString}
        );
        
        // Perform layout and measure time
        String[] output = new String[1];
        tracker.timeMaskingOperation("json_string_zoloz_response", () -> {
            output[0] = layout.doLayout(event);
        });
        
        // Parse output to verify structure
        JsonNode outputJson = MAPPER.readTree(output[0]);
        
        // Verify basic structure
        assertThat(outputJson.has("timestamp")).isTrue();
        assertThat(outputJson.has("level")).isTrue();
        assertThat(outputJson.has("message")).isTrue();
        
        // Verify ID_NUMBER and other PII are masked in the message
        String message = outputJson.get("message").asText();
        assertThat(message).contains("[REDACTED]");
        assertThat(message).doesNotContain("C123456(9)"); // ID_NUMBER masked
        assertThat(message).doesNotContain("CHAN, Tai Man David"); // NAME masked
        
        System.out.println("\n📝 JSON String Masking Test Output:");
        System.out.println(output[0]);
    }

    /**
     * Test Case 2: DTO Masking
     * <p>
     * Reads the Zoloz response JSON file, deserializes it to ZolozCheckResultResponse DTO,
     * and logs the DTO object.
     * Tests that ID_NUMBER and other PII fields are properly masked when logging DTOs.
     */
    @Test
    void testDtoMasking_ZolozResponse() throws Exception {
        // Read JSON file as string
        String jsonPath = "src/test/resources/zoloz/checkResult_response_hkid.json";
        String jsonString = Files.readString(Paths.get(jsonPath));
        
        // Deserialize to DTO
        ZolozCheckResultResponse response = MAPPER.readValue(jsonString, ZolozCheckResultResponse.class);
        
        // Verify DTO contains unmasked ID_NUMBER
        assertThat(response.getExtInfo().getOcrResult().get("ID_NUMBER")).isEqualTo("C123456(9)");
        assertThat(response.getExtInfo().getOcrResult().get("NAME")).isEqualTo("CHAN, Tai Man David");
        assertThat(response.getExtInfo().getOcrResult().get("NAME_CN")).isEqualTo("陳大文");
        
        // Create logging event with DTO as argument
        ILoggingEvent event = createLoggingEvent(
            "Receive Zoloz Check Result Response: {}", 
            new Object[]{response}
        );
        
        // Perform layout and measure time
        String[] output = new String[1];
        tracker.timeMaskingOperation("dto_zoloz_response", () -> {
            output[0] = layout.doLayout(event);
        });
        
        // Parse output to verify structure
        JsonNode outputJson = MAPPER.readTree(output[0]);
        
        // Verify basic structure
        assertThat(outputJson.has("timestamp")).isTrue();
        assertThat(outputJson.has("level")).isTrue();
        assertThat(outputJson.has("message")).isTrue();
        
        // Verify ID_NUMBER and other PII are masked in the message
        String message = outputJson.get("message").asText();
        assertThat(message).contains("[REDACTED]");
        assertThat(message).doesNotContain("C123456(9)"); // ID_NUMBER masked
        assertThat(message).doesNotContain("CHAN, Tai Man David"); // NAME masked
        
        // Parse the JSON within the message to verify nested masking
        String jsonInMessage = message.substring(message.indexOf("{"));
        JsonNode maskedResponse = MAPPER.readTree(jsonInMessage);
        
        // Verify specific sections have all fields masked
        // Note: Masking "ocrResult" masks ALL fields within it (not replacing the object itself)
        JsonNode extInfo = maskedResponse.get("extInfo");
        assertThat(extInfo).isNotNull();
        
        // ocrResult should have all fields masked (even non-PII like SEX)
        JsonNode ocrResult = extInfo.get("ocrResult");
        assertThat(ocrResult.isObject()).as("ocrResult remains an object").isTrue();
        assertThat(ocrResult.get("ID_NUMBER").asText()).isEqualTo("[REDACTED]");
        assertThat(ocrResult.get("NAME").asText()).isEqualTo("[REDACTED]");
        assertThat(ocrResult.get("SEX").asText()).isEqualTo("[REDACTED]"); // All fields masked
        
        // ocrResultFormat should have all fields masked
        JsonNode ocrResultFormat = extInfo.get("ocrResultFormat");
        assertThat(ocrResultFormat.isObject()).as("ocrResultFormat remains an object").isTrue();
        assertThat(ocrResultFormat.get("NUMBER").asText()).isEqualTo("[REDACTED]");
        assertThat(ocrResultFormat.get("GENDER").asText()).isEqualTo("[REDACTED]"); // All fields masked
        
        // ocrResultDetail should have all fields masked
        JsonNode ocrResultDetail = extInfo.get("ocrResultDetail");
        assertThat(ocrResultDetail.isObject()).as("ocrResultDetail remains an object").isTrue();
        assertThat(ocrResultDetail.get("MRZ_ID_NUMBER").asText()).isEqualTo("[REDACTED]");
        
        // Verify non-PII fields are NOT masked
        assertThat(extInfo.get("certType").asText()).isEqualTo("08520000002");
        assertThat(extInfo.get("docCategory").asText()).isEqualTo("ID_CARD");
        
        System.out.println("\n📝 DTO Masking Test Output:");
        System.out.println(output[0]);
    }

    /**
     * Test Case 3: Nested Fields Masking
     * <p>
     * Verifies that deeply nested PII fields (like ocrResultDetail) are properly masked.
     */
    @Test
    void testNestedFieldsMasking_ZolozResponse() throws Exception {
        // Read and deserialize
        String jsonPath = "src/test/resources/zoloz/checkResult_response_hkid.json";
        String jsonString = Files.readString(Paths.get(jsonPath));
        ZolozCheckResultResponse response = MAPPER.readValue(jsonString, ZolozCheckResultResponse.class);
        
        // Create logging event
        ILoggingEvent event = createLoggingEvent(
            "Processing Zoloz response with nested fields: {}", 
            new Object[]{response}
        );
        
        // Perform layout
        String output = layout.doLayout(event);
        
        // Parse output
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        String jsonInMessage = message.substring(message.indexOf("{"));
        JsonNode maskedResponse = MAPPER.readTree(jsonInMessage);
        
        // Verify ocrResultDetail section has all fields masked
        // Note: Masking "ocrResultDetail" masks ALL fields within the object (not replacing the object itself)
        JsonNode ocrResultDetail = maskedResponse.get("extInfo").get("ocrResultDetail");
        
        // ocrResultDetail should remain an object with all fields masked
        assertThat(ocrResultDetail.isObject())
            .as("ocrResultDetail should remain an object")
            .isTrue();
        
        // All fields within should be [REDACTED]
        assertThat(ocrResultDetail.get("MRZ_ID_NUMBER").asText())
            .as("MRZ_ID_NUMBER should be masked")
            .isEqualTo("[REDACTED]");
        assertThat(ocrResultDetail.get("MRZ_NAME").asText())
            .as("MRZ_NAME should be masked")
            .isEqualTo("[REDACTED]");
        assertThat(ocrResultDetail.get("MRZ_NAME_CN").asText())
            .as("MRZ_NAME_CN should be masked")
            .isEqualTo("[REDACTED]");
        
        // This approach masks ALL data within the section, providing comprehensive protection
        
        System.out.println("\n📝 Nested Fields Masking Test - Verified all fields in ocrResultDetail are masked");
    }

    /**
     * Test Case 4: ocrResultFormat Masking
     * <p>
     * Verifies that ocrResultFormat fields are properly masked.
     */
    @Test
    void testOcrResultFormatMasking_ZolozResponse() throws Exception {
        // Read and deserialize
        String jsonPath = "src/test/resources/zoloz/checkResult_response_hkid.json";
        String jsonString = Files.readString(Paths.get(jsonPath));
        ZolozCheckResultResponse response = MAPPER.readValue(jsonString, ZolozCheckResultResponse.class);
        
        // Create logging event
        ILoggingEvent event = createLoggingEvent(
            "Zoloz response format check: {}", 
            new Object[]{response}
        );
        
        // Perform layout
        String output = layout.doLayout(event);
        
        // Parse output
        JsonNode outputJson = MAPPER.readTree(output);
        String message = outputJson.get("message").asText();
        String jsonInMessage = message.substring(message.indexOf("{"));
        JsonNode maskedResponse = MAPPER.readTree(jsonInMessage);
        
        // Verify ocrResultFormat section has all fields masked
        // Note: Masking "ocrResultFormat" masks ALL fields within the object (not replacing the object itself)
        JsonNode ocrResultFormat = maskedResponse.get("extInfo").get("ocrResultFormat");
        
        // ocrResultFormat should remain an object with all fields masked
        assertThat(ocrResultFormat.isObject())
            .as("ocrResultFormat should remain an object")
            .isTrue();
        
        // All fields within should be [REDACTED] (including non-PII)
        assertThat(ocrResultFormat.get("NUMBER").asText())
            .as("NUMBER should be masked")
            .isEqualTo("[REDACTED]");
        assertThat(ocrResultFormat.get("FULL_NAME").asText())
            .as("FULL_NAME should be masked")
            .isEqualTo("[REDACTED]");
        assertThat(ocrResultFormat.get("FULL_NAME_EN").asText())
            .as("FULL_NAME_EN should be masked")
            .isEqualTo("[REDACTED]");
        assertThat(ocrResultFormat.get("GENDER").asText())
            .as("GENDER should also be masked (all fields in section are masked)")
            .isEqualTo("[REDACTED]");
        assertThat(ocrResultFormat.get("DATE_OF_BIRTH").asText())
            .as("DATE_OF_BIRTH should also be masked")
            .isEqualTo("[REDACTED]");
        
        // This approach provides comprehensive protection of the entire section
        
        System.out.println("\n📝 ocrResultFormat Masking Test - Verified all fields in section are masked");
    }

    /**
     * Test Case 5: Performance Comparison
     * <p>
     * Compares performance between JSON string and DTO masking.
     */
    @Test
    void testPerformanceComparison_JsonStringVsDto() throws Exception {
        String jsonPath = "src/test/resources/zoloz/checkResult_response_hkid.json";
        String jsonString = Files.readString(Paths.get(jsonPath));
        ZolozCheckResultResponse response = MAPPER.readValue(jsonString, ZolozCheckResultResponse.class);
        
        // Test JSON string masking (5 runs)
        for (int i = 0; i < 5; i++) {
            ILoggingEvent event = createLoggingEvent("JSON string run {}: {}", new Object[]{i + 1, jsonString});
            int runNum = i;
            tracker.timeMaskingOperation("json_string_run_" + (runNum + 1), () -> {
                layout.doLayout(event);
            });
        }
        
        // Test DTO masking (5 runs)
        for (int i = 0; i < 5; i++) {
            ILoggingEvent event = createLoggingEvent("DTO run {}: {}", new Object[]{i + 1, response});
            int runNum = i;
            tracker.timeMaskingOperation("dto_run_" + (runNum + 1), () -> {
                layout.doLayout(event);
            });
        }
        
        System.out.println("\n📊 Performance Comparison Complete - See summary above");
    }

    // Helper methods

    private ILoggingEvent createLoggingEvent(String message, Object[] args) {
        Logger logger = loggerContext.getLogger("com.example.test");
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(logger.getName());
        event.setLoggerContext(loggerContext);
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setArgumentArray(args);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }
}


package com.example.service;

import com.example.dto.OcrResultDetail;
import com.example.dto.UserProfile;
import com.example.dto.ZolozResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Demo service to test PII masking functionality
 */
@Service
public class DemoService {
    
    private static final Logger logger = LoggerFactory.getLogger(DemoService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Simulates processing a Zoloz eKYC response with PII data
     */
    public ZolozResponse processZolozResponse() {
        logger.info("Starting Zoloz eKYC response processing");
        
        // Create sample OCR result details with name-value pairs
        List<OcrResultDetail> ocrDetails = Arrays.asList(
            new OcrResultDetail("NAME", "John Doe", 0.95),
            new OcrResultDetail("ID_NUMBER", "1234567890", 0.98),
            new OcrResultDetail("DATE_OF_BIRTH", "1990-01-15", 0.92),
            new OcrResultDetail("SYMBOLS", "ABC123", 0.87)
        );
        
        // Create Zoloz response with PII data
        ZolozResponse response = new ZolozResponse();
        response.setName("John Doe");
        response.setNameCn("约翰·多伊");
        response.setNameCnRaw("约翰·多伊");
        response.setIdNumber("1234567890");
        response.setDateOfBirth("1990-01-15");
        response.setStandardizedDateOfBirth("1990-01-15");
        response.setLatestIssueDate("2020-05-20");
        response.setStandardizedLatestIssueDate("2020-05-20");
        response.setChineseCommercialCode("CHN001");
        response.setSymbols("ABC123");
        response.setOcrResultDetail(ocrDetails);
        response.setStatus("SUCCESS");
        response.setMessage("Verification completed successfully");
        
        // This log message will trigger PII masking
        try {
            String responseJson = objectMapper.writeValueAsString(response);
            logger.info("Received ekyc response from zoloz: {}", responseJson);
            
            // Test with nested JSON structure
            String ocrDetailsJson = objectMapper.writeValueAsString(ocrDetails);
            logger.info("Processing OCR details: {}", ocrDetailsJson);
        } catch (Exception e) {
            logger.error("Error serializing response to JSON", e);
        }
        
        return response;
    }

    /**
     * Simulates processing user profile data with various PII fields
     */
    public UserProfile processUserProfile() {
        logger.info("Starting user profile processing");
        
        UserProfile profile = new UserProfile();
        profile.setSsn("123-45-6789");
        profile.setEmail("john.doe@example.com");
        profile.setCreditCardNumber("4111-1111-1111-1111");
        profile.setPhoneNumber("+1-555-123-4567");
        profile.setName("John Doe");
        profile.setIdNumber("ID123456789");
        profile.setDateOfBirth("1990-01-15");
        profile.setAddress("123 Main Street");
        profile.setCity("New York");
        profile.setCountry("USA");
        
        // This log message will trigger PII masking
        try {
            String profileJson = objectMapper.writeValueAsString(profile);
            logger.info("Processing user profile data: {}", profileJson);
            
            // Test with simple JSON string
            logger.info("User SSN: {}", profile.getSsn());
            logger.info("User email: {}", profile.getEmail());
        } catch (Exception e) {
            logger.error("Error serializing profile to JSON", e);
        }
        
        return profile;
    }

    /**
     * Simulates processing a simple JSON string with PII
     */
    public void processSimpleJson() {
        logger.info("Processing simple JSON with PII data");
        
        String jsonWithPii = "{\"NAME\":\"Jane Smith\",\"SSN\":\"987-65-4321\",\"EMAIL\":\"jane.smith@example.com\",\"CREDIT_CARD_NUMBER\":\"5555-4444-3333-2222\"}";
        
        // This log message will trigger PII masking
        logger.info("Simple JSON data: {}", jsonWithPii);
        
        // Test with mixed content
        logger.info("User data received: {}", jsonWithPii);
    }

    /**
     * Simulates processing data without PII (should not be masked)
     */
    public void processNonPiiData() {
        logger.info("Processing non-PII data");
        
        String nonPiiJson = "{\"status\":\"success\",\"timestamp\":\"2024-01-15T10:30:00Z\",\"requestId\":\"req-12345\"}";
        
        // This log message should not trigger any masking
        logger.info("System status: {}", nonPiiJson);
        
        logger.info("Processing completed without PII data");
    }

    /**
     * Simulates error logging with PII data
     */
    public void processErrorWithPii() {
        logger.info("Simulating error scenario with PII data");
        
        try {
            // Simulate an error scenario
            throw new RuntimeException("Database connection failed");
        } catch (Exception e) {
            UserProfile errorProfile = new UserProfile("999-99-9999", "error@example.com", "0000-0000-0000-0000", "555-ERROR", "Error User");
            
            // Error logs with PII should also be masked
            try {
                String errorProfileJson = objectMapper.writeValueAsString(errorProfile);
                logger.error("Error processing user data: {} - Exception: {}", errorProfileJson, e.getMessage(), e);
            } catch (Exception jsonEx) {
                logger.error("Error serializing error profile to JSON", jsonEx);
            }
        }
    }
}
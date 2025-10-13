package com.example.service;

import com.example.dto.OcrResultDetail;
import com.example.dto.UserProfile;
import com.example.dto.ZolozCheckResultResponse;
import com.example.dto.ZolozResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    public ZolozCheckResultResponse processZolozCheckResultResponse(String req)
            throws JsonProcessingException {
        logger.info("Received Zoloz Check Result Response String: {}", req);
        var response = objectMapper.readValue(req, ZolozCheckResultResponse.class);
        logger.info("Received Zoloz Check Result Response: {}", response);
        return response;
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
}
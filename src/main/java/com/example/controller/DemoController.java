package com.example.controller;

import com.example.dto.UserProfile;
import com.example.dto.ZolozResponse;
import com.example.service.DemoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Demo controller to test PII masking functionality
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {
    
    private static final Logger logger = LoggerFactory.getLogger(DemoController.class);
    
    @Autowired
    private DemoService demoService;

    /**
     * Test endpoint for Zoloz eKYC response processing
     */
    @GetMapping("/zoloz")
    public ResponseEntity<Map<String, Object>> testZolozResponse() {
        logger.info("API endpoint called: /api/demo/zoloz");
        
        ZolozResponse response = demoService.processZolozResponse();
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Zoloz response processed successfully");
        result.put("status", response.getStatus());
        result.put("data", response);
        
        return ResponseEntity.ok(result);
    }

    /**
     * Test endpoint for user profile processing
     */
    @GetMapping("/user-profile")
    public ResponseEntity<Map<String, Object>> testUserProfile() {
        logger.info("API endpoint called: /api/demo/user-profile");
        
        UserProfile profile = demoService.processUserProfile();
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "User profile processed successfully");
        result.put("data", profile);
        
        return ResponseEntity.ok(result);
    }

    /**
     * Test endpoint for simple JSON processing
     */
    @GetMapping("/simple-json")
    public ResponseEntity<Map<String, Object>> testSimpleJson() {
        logger.info("API endpoint called: /api/demo/simple-json");
        
        demoService.processSimpleJson();
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Simple JSON processed successfully");
        result.put("note", "Check logs to see PII masking in action");
        
        return ResponseEntity.ok(result);
    }

    /**
     * Test endpoint for non-PII data processing
     */
    @GetMapping("/non-pii")
    public ResponseEntity<Map<String, Object>> testNonPiiData() {
        logger.info("API endpoint called: /api/demo/non-pii");
        
        demoService.processNonPiiData();
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Non-PII data processed successfully");
        result.put("note", "Check logs - no masking should occur");
        
        return ResponseEntity.ok(result);
    }

    /**
     * Test endpoint for error scenarios with PII
     */
    @GetMapping("/error-with-pii")
    public ResponseEntity<Map<String, Object>> testErrorWithPii() {
        logger.info("API endpoint called: /api/demo/error-with-pii");
        
        try {
            demoService.processErrorWithPii();
        } catch (Exception e) {
            logger.error("Error in error test endpoint", e);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Error scenario processed");
        result.put("note", "Check logs to see PII masking in error messages");
        
        return ResponseEntity.ok(result);
    }

    /**
     * Test endpoint to run all demo scenarios
     */
    @GetMapping("/all")
    public ResponseEntity<Map<String, Object>> testAllScenarios() {
        logger.info("API endpoint called: /api/demo/all - Running all demo scenarios");
        
        Map<String, Object> results = new HashMap<>();
        
        try {
            // Test Zoloz response
            ZolozResponse zolozResponse = demoService.processZolozResponse();
            results.put("zoloz", "SUCCESS");
            
            // Test user profile
            UserProfile userProfile = demoService.processUserProfile();
            results.put("userProfile", "SUCCESS");
            
            // Test simple JSON
            demoService.processSimpleJson();
            results.put("simpleJson", "SUCCESS");
            
            // Test non-PII data
            demoService.processNonPiiData();
            results.put("nonPii", "SUCCESS");
            
            // Test error with PII
            demoService.processErrorWithPii();
            results.put("errorWithPii", "SUCCESS");
            
            results.put("message", "All demo scenarios completed successfully");
            results.put("note", "Check application logs to see PII masking in action");
            
        } catch (Exception e) {
            logger.error("Error running demo scenarios", e);
            results.put("error", e.getMessage());
        }
        
        return ResponseEntity.ok(results);
    }

    // ==================== Environment Variable Configuration ====================

    /**
     * Show current environment variable configuration
     */
    @GetMapping("/env-config")
    public ResponseEntity<Map<String, Object>> getEnvConfig() {
        logger.info("API endpoint called: /api/demo/env-config");
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "PII masking configuration via environment variables");
        result.put("PII_MASK_KEYS", System.getenv("PII_MASK_KEYS"));
        result.put("PII_MASK_TEXT", System.getenv("PII_MASK_TEXT"));
        result.put("note", "Set PII_MASK_KEYS and PII_MASK_TEXT environment variables to configure PII masking");
        result.put("example", "export PII_MASK_KEYS='NAME,SSN,EMAIL,CREDIT_CARD' && export PII_MASK_TEXT='[MASKED]'");
        
        return ResponseEntity.ok(result);
    }
}
package com.example.controller;

import com.example.dto.ZolozCheckResultResponse;
import com.example.service.DemoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo controller to test PII masking functionality
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
@Slf4j
public class DemoController {

    private final DemoService demoService;

    @PostMapping("/zoloz/check-result")
    public ResponseEntity<ZolozCheckResultResponse> testZolozCheckResultResponse(@RequestBody String req)
            throws JsonProcessingException {
        log.info("API endpoint called: /api/demo/zoloz/check-result");

        ZolozCheckResultResponse response = demoService.processZolozCheckResultResponse(req);

        return ResponseEntity.ok(response);
    }
    // ==================== Environment Variable Configuration ====================

    /**
     * Show current environment variable configuration
     */
    @GetMapping("/env-config")
    public ResponseEntity<Map<String, Object>> getEnvConfig() {
        log.info("API endpoint called: /api/demo/env-config");

        Map<String, Object> result = new HashMap<>();
        result.put("message", "PII masking configuration via environment variables");
        result.put("PII_MASK_KEYS", System.getenv("PII_MASK_KEYS"));
        result.put("PII_MASK_TEXT", System.getenv("PII_MASK_TEXT"));
        result.put("note", "Set PII_MASK_KEYS and PII_MASK_TEXT environment variables to configure PII masking");
        result.put("example", "export PII_MASK_KEYS='NAME,SSN,EMAIL,CREDIT_CARD' && export PII_MASK_TEXT='[MASKED]'");

        return ResponseEntity.ok(result);
    }
}
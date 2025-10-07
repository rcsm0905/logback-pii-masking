package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for demonstrating PII data masking with Logback
 */
@SpringBootApplication
public class LogbackMaskingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogbackMaskingApplication.class, args);
    }
}
package com.example.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OCR result detail with name-value pairs for PII masking
 */
public class OcrResultDetail {
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("value")
    private String value;
    
    @JsonProperty("confidence")
    private Double confidence;

    // Constructors
    public OcrResultDetail() {}

    public OcrResultDetail(String name, String value, Double confidence) {
        this.name = name;
        this.value = value;
        this.confidence = confidence;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
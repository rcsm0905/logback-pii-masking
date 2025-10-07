package com.example.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Sample DTO representing a Zoloz eKYC response with PII data
 */
public class ZolozResponse {
    
    @JsonProperty("NAME")
    private String name;
    
    @JsonProperty("NAME_CN")
    private String nameCn;
    
    @JsonProperty("NAME_CN_RAW")
    private String nameCnRaw;
    
    @JsonProperty("ID_NUMBER")
    private String idNumber;
    
    @JsonProperty("DATE_OF_BIRTH")
    private String dateOfBirth;
    
    @JsonProperty("STANDARDIZED_DATE_OF_BIRTH")
    private String standardizedDateOfBirth;
    
    @JsonProperty("LATEST_ISSUE_DATE")
    private String latestIssueDate;
    
    @JsonProperty("STANDARDIZED_LATEST_ISSUE_DATE")
    private String standardizedLatestIssueDate;
    
    @JsonProperty("CHINESE_COMMERCIAL_CODE")
    private String chineseCommercialCode;
    
    @JsonProperty("SYMBOLS")
    private String symbols;
    
    @JsonProperty("ocrResultDetail")
    private List<OcrResultDetail> ocrResultDetail;
    
    @JsonProperty("status")
    private String status;
    
    @JsonProperty("message")
    private String message;

    // Constructors
    public ZolozResponse() {}

    public ZolozResponse(String name, String nameCn, String idNumber, String dateOfBirth) {
        this.name = name;
        this.nameCn = nameCn;
        this.idNumber = idNumber;
        this.dateOfBirth = dateOfBirth;
        this.status = "SUCCESS";
        this.message = "Verification completed";
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNameCn() {
        return nameCn;
    }

    public void setNameCn(String nameCn) {
        this.nameCn = nameCn;
    }

    public String getNameCnRaw() {
        return nameCnRaw;
    }

    public void setNameCnRaw(String nameCnRaw) {
        this.nameCnRaw = nameCnRaw;
    }

    public String getIdNumber() {
        return idNumber;
    }

    public void setIdNumber(String idNumber) {
        this.idNumber = idNumber;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getStandardizedDateOfBirth() {
        return standardizedDateOfBirth;
    }

    public void setStandardizedDateOfBirth(String standardizedDateOfBirth) {
        this.standardizedDateOfBirth = standardizedDateOfBirth;
    }

    public String getLatestIssueDate() {
        return latestIssueDate;
    }

    public void setLatestIssueDate(String latestIssueDate) {
        this.latestIssueDate = latestIssueDate;
    }

    public String getStandardizedLatestIssueDate() {
        return standardizedLatestIssueDate;
    }

    public void setStandardizedLatestIssueDate(String standardizedLatestIssueDate) {
        this.standardizedLatestIssueDate = standardizedLatestIssueDate;
    }

    public String getChineseCommercialCode() {
        return chineseCommercialCode;
    }

    public void setChineseCommercialCode(String chineseCommercialCode) {
        this.chineseCommercialCode = chineseCommercialCode;
    }

    public String getSymbols() {
        return symbols;
    }

    public void setSymbols(String symbols) {
        this.symbols = symbols;
    }

    public List<OcrResultDetail> getOcrResultDetail() {
        return ocrResultDetail;
    }

    public void setOcrResultDetail(List<OcrResultDetail> ocrResultDetail) {
        this.ocrResultDetail = ocrResultDetail;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
package com.example.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Sample user profile DTO with various PII fields for testing
 */
public class UserProfile {
    
    @JsonProperty("SSN")
    private String ssn;
    
    @JsonProperty("EMAIL")
    private String email;
    
    @JsonProperty("CREDIT_CARD_NUMBER")
    private String creditCardNumber;
    
    @JsonProperty("PHONE_NUMBER")
    private String phoneNumber;
    
    @JsonProperty("NAME")
    private String name;
    
    @JsonProperty("ID_NUMBER")
    private String idNumber;
    
    @JsonProperty("DATE_OF_BIRTH")
    private String dateOfBirth;
    
    @JsonProperty("address")
    private String address;
    
    @JsonProperty("city")
    private String city;
    
    @JsonProperty("country")
    private String country;

    // Constructors
    public UserProfile() {}

    public UserProfile(String ssn, String email, String creditCardNumber, String phoneNumber, String name) {
        this.ssn = ssn;
        this.email = email;
        this.creditCardNumber = creditCardNumber;
        this.phoneNumber = phoneNumber;
        this.name = name;
    }

    // Getters and Setters
    public String getSsn() {
        return ssn;
    }

    public void setSsn(String ssn) {
        this.ssn = ssn;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCreditCardNumber() {
        return creditCardNumber;
    }

    public void setCreditCardNumber(String creditCardNumber) {
        this.creditCardNumber = creditCardNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }
}
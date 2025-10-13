package com.example.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZolozCheckResultResponse {
    ZolozApiResult result;
    ExtInfo extInfo;

    @Data
    @FieldDefaults(level = AccessLevel.PRIVATE)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExtInfo {
        String recognitionResult;
        String certType;
        String docCategory;
        Integer docEdition;
        List<String> imageContent;
        Map<String, String> extraImages;
        Map<String, String> ocrResult;
        Map<String, String> ocrResultFormat;
        Map<String, OcrResultDetailItem> ocrResultDetail;
        String countryCode;
        Map<String, String> spoofResult;
        String recognitionErrorCode;
        String recognitionErrorDescription;

        @Data
        @FieldDefaults(level = AccessLevel.PRIVATE)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class OcrResultDetailItem {
            String name;
            String source;
            String value;
        }
    }
}
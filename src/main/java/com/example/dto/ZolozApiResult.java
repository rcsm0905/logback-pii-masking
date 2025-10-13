package com.example.dto;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

/**
 * Represents the result code and status of a Zoloz transaction.
 * <p>
 * Possible values and their meanings:
 * <ul>
 *   <li>{@code SUCCESS} (Status: S) - User finished the transaction.</li>
 *   <li>{@code PROCESSING} (Status: S) - User hasn't finished the whole flow.</li>
 *   <li>{@code INVALID_ARGUMENT} (Status: F) - Input parameters are invalid. For more information about which parameter is invalid, check the result message or the related log.</li>
 *   <li>{@code SYSTEM_ERROR} (Status: F) - Other internal errors. For more information about the error details, check the result message or the related log.</li>
 *   <li>{@code UNUSABLE} (Status: F) - User is blocked by the cooldown strategy.</li>
 *   <li>{@code LIMIT_EXCEEDED} (Status: F) - User tried too many times within the same transaction.</li>
 * </ul>
 */
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ZolozApiResult {
    private String resultCode;

    /**
     * "S": successful
     * "F": failed
     * "U": unknown issue
     */
    private String resultStatus;

    private String resultMessage;
}

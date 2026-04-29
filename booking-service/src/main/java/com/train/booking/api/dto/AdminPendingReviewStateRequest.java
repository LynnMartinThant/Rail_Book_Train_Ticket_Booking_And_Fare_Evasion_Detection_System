package com.train.booking.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminPendingReviewStateRequest {
    /** Allowed: PENDING_REVIEW, ESCALATED_FRAUD_REVIEW, CLOSED */
    @NotBlank
    private String state;
    /** Optional operator note for examiner/demo traceability. */
    private String note;
}

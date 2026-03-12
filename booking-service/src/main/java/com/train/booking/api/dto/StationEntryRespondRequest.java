package com.train.booking.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class StationEntryRespondRequest {
    @NotBlank(message = "choice is required")
    @Pattern(regexp = "IGNORE|BUY_TICKET|SCAN_QR", message = "choice must be IGNORE, BUY_TICKET, or SCAN_QR")
    private String choice;
}

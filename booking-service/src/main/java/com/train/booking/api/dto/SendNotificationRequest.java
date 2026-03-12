package com.train.booking.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SendNotificationRequest {
    @NotNull(message = "userId is required")
    @NotBlank(message = "userId is required")
    private String userId;
    @NotNull(message = "message is required")
    @NotBlank(message = "message is required")
    private String message;
}

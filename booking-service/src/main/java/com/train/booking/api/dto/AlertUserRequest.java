package com.train.booking.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AlertUserRequest {
    @NotNull(message = "userId is required")
    private String userId;
    @NotNull(message = "tripId is required")
    private Long tripId;
}

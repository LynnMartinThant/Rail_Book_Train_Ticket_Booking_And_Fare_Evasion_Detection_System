package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class UserLocationDto {
    private String userId;
    private Double latitude;
    private Double longitude;
    private Instant updatedAt;
}

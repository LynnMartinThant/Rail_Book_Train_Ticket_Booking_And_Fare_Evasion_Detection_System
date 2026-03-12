package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {
    private String userId;  // string id for X-User-Id header
    private String email;
}

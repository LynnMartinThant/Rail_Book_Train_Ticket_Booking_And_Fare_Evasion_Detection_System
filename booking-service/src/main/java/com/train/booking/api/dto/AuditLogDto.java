package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AuditLogDto { // Audit row for API
    private Long id;
    private String userId; 
    private String action;
    private String details;
    private Instant createdAt;
}

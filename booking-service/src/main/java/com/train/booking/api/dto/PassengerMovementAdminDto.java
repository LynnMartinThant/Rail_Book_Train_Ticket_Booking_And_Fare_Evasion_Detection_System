package com.train.booking.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Admin read model: derived passenger movement state (no raw GPS).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PassengerMovementAdminDto {
    private String userId;
    private String currentStation;
    private String currentPlatform;
    private String lastGeofenceEventType;
    private String journeyStatus;
    private String candidateOriginStation;
    private Instant lastEventAt;
    private Long lastConfirmedSegmentId;
    private Instant updatedAt;
}

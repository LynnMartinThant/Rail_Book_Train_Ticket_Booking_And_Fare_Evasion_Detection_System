package com.train.booking.movement.stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Per-user movement state for journey coordination (Kafka Streams aggregate or in-memory fallback).
 * Aligns with platform {@link com.train.booking.platform.JourneyStatus} as string for JSON serde.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PassengerMovementState {
    private String userId;
    private String currentStation;
    private String currentPlatform;
    /** ENTERED or EXITED (last geofence transition). */
    private String lastTransitionType;
    private Instant lastTransitionTime;
    private String lastGeofenceEventType;
    private Instant lastEventAt;
    private String candidateOriginStation;
    private Instant candidateOriginTime;
    private String journeyStatus;
    private String lastProcessedEventId;
    private String lastEmittedSegmentKey;
    /** Latest confirmed trip segment id, when known */
    private Long lastConfirmedSegmentId;
}

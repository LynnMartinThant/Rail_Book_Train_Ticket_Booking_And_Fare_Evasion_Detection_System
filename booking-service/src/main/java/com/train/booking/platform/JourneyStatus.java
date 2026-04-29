package com.train.booking.platform;

/**
 * Coordination-layer journey status for a passenger (not persisted as enum on all paths; used in state/read models).
 */
public enum JourneyStatus {
    IDLE,
    AT_STATION,
    IN_TRANSIT,
    ARRIVED,
    SEGMENT_CONFIRMED
}

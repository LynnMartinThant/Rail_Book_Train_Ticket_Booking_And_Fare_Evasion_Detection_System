package com.train.booking.movement.eventlog;

public enum MovementEventType {
    LocationReported,
    DataQualityAssessed,
    LocationRejected,
    GeofenceEntered,
    GeofenceExited,
    /** Station/local decision: ticket entitlement at station entry (valid/invalid/review). */
    StationEntryValidated,
    StationVisitStarted,
    StationVisitEnded,
    /** Journey coordination: read-model / state snapshot */
    PassengerStateUpdated,
    JourneySegmentConfirmed,
    FareValidated,
    FraudDecisionMade,
    DisputeSubmitted,
    SegmentDecisionUpdated
}


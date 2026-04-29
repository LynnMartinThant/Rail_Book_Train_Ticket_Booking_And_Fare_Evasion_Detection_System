package com.train.booking.platform;

/**
 * Owning layer for movement-domain events (hierarchical platform).
 */
public enum MovementSourceLayer {
    /** Legacy / unspecified */
    UNKNOWN,
    /** Raw GPS and request validation — {@code movement-ingestion} */
    MOVEMENT_INGESTION,
    /** Geofence registry, enter/exit facts — {@code station-processing} */
    STATION_PROCESSING,
    /** Passenger state, journey reconstruction — {@code journey-coordination} */
    JOURNEY_COORDINATION,
    /** Ticket coverage vs travelled segment — {@code fare-policy} */
    FARE_POLICY,
    /** Suspicious patterns, escalation — {@code fraud-policy} */
    FRAUD_POLICY,
    /** Disputes, overrides (future) — {@code admin-supervision} */
    ADMIN_SUPERVISION
}

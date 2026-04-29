package com.train.booking.domain;

public enum FareStatus {
    PAID,
    UNDERPAID,
    /** Decision is uncertain; flag for manual review (confidence-driven). */
    PENDING_REVIEW,
    PENDING_RESOLUTION,
    UNPAID_TRAVEL
}

package com.train.booking.domain;

/**
 * Fare status for a trip segment after ticket validation.
 * PAID = valid ticket covers full route; UNDERPAID = short ticket; UNPAID_TRAVEL = penalty applied.
 * PENDING_RESOLUTION = unauthorised travel detected, user has 1 hour to resolve (buy/upload ticket or penalty will be applied).
 */
public enum FareStatus {
    PAID,
    UNDERPAID,
    PENDING_RESOLUTION,
    UNPAID_TRAVEL
}

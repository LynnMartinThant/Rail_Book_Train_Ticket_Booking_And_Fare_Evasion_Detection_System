package com.train.booking.domain;

/**
 * Ticket/Reservation state machine for payment flow.
 * Never trust the client; always verify payment server-side (webhook/transaction ID).
 */
public enum ReservationStatus {
    /** Legacy: seat held, awaiting payment (manual ref). New flow uses PENDING_PAYMENT. */
    RESERVED,
    /** Temporary booking created; not yet paid. */
    PENDING_PAYMENT,
    /** User redirected to gateway; payment in progress. */
    PAYMENT_PROCESSING,
    /** Payment confirmed by gateway (webhook). */
    PAID,
    /** Booking confirmed (ticket valid for travel). */
    CONFIRMED,
    /** Payment failed, expired, or cancelled. */
    CANCELLED,
    /** Ticket used for travel (e.g. segment completed). */
    USED,
    /** Reservation timed out. */
    EXPIRED,
    /** Refund approved; seat available again. */
    REFUNDED
}

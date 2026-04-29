# Fraud Detection Engine & Three Engines Overview

This document describes the **Fraud Detection Engine** (ticket sharing, refund fraud, multi-device, chargeback, suspicious account, system reliability) and the three core engines: **Geofencing**, **Journey Reconstruction**, and **Fraud Detection Rules** (Drools).

For the six **detection rules** (No Ticket, Over Travel, Route Violation, Ticket Sharing, Suspicious Pattern, Low Confidence) and how the system detects each, see **[DETECTION-RULES.md](DETECTION-RULES.md)**.

---

## Fraud Detection Engine

The Fraud Detection Engine is a single layer that runs multiple checks (scheduled or on-demand via `POST /api/admin/fraud-detection/run`) and writes audit events for investigation.

### 1. Ticket Sharing

- **What:** Same reservation (ticket) used by different passengers with overlapping journey times.
- **Tech:** `TripSegmentService.detectTicketSharing()` – groups segments by `reservationId`, finds distinct `passengerId` and overlapping time windows.
- **Action:** Audit log `FRAUD_TICKET_SHARING` with reservation and segment IDs; scheduled via `booking.fraud.ticket-sharing-cron` (default hourly).
- **API:** `POST /api/admin/detect-ticket-sharing` (runs ticket-sharing only); full run includes it.

### 2. Refund Fraud

- **What:** (1) Refund requested for a reservation that has already been used in a trip segment (travel completed). (2) User with abnormally high number of refund requests in a time window.
- **Tech:** `FraudDetectionService.runRefundFraudDetection()` – PENDING refund requests checked against `TripSegment.reservationId`; per-user refund count in last N days.
- **Config:** `booking.fraud.refund-fraud-max-requests-per-user`, `booking.fraud.refund-fraud-window-days`.
- **Action:** Audit `FRAUD_REFUND_AFTER_TRAVEL` or `FRAUD_REFUND_ABUSE`.

### 3. Multiple Device Usage

- **What:** Same user (passenger) with two journey segments that overlap in time but have different origin/destination (impossible physical journey – suggests shared account or multiple devices).
- **Tech:** `FraudDetectionService.runMultipleDeviceDetection()` – segments grouped by `passengerId`, overlapping time windows with different routes flagged.
- **Action:** Audit `FRAUD_MULTIPLE_DEVICE` with segment IDs.

### 4. Payment Chargeback Fraud

- **What:** Payment gateway (Stripe) reports a dispute/chargeback.
- **Tech:** `PaymentGatewayService.handleStripeWebhook()` handles `charge.dispute.created` and `charge.dispute.updated`; calls `FraudDetectionService.recordChargeback()`.
- **Action:** Audit `FRAUD_CHARGEBACK` with event/reference; optional downstream (e.g. mark reservation, notify finance).

### 5. Suspicious Account Activity

- **What:** User with many fare evasion events or many segments in PENDING_RESOLUTION (repeated no-ticket travel).
- **Tech:** `FraudDetectionService.runSuspiciousAccountDetection()` – counts FARE_EVASION audit entries and PENDING_RESOLUTION segments per user.
- **Config:** `booking.fraud.suspicious-account-min-evasion-count`, `booking.fraud.suspicious-account-min-pending-resolution`.
- **Action:** Audit `FRAUD_SUSPICIOUS_ACCOUNT`.

### System Reliability (consistency and safeguards)

- **Concurrency control:** Pessimistic locking on `TripSeat` and `Reservation` during reserve/payment (e.g. `findByTripIdAndSeatIdForUpdate`, `findByIdAndUserIdForUpdate`) to prevent double booking and race conditions.
- **Race conditions:** Idempotency on segment creation (`idempotencyKey`), webhook handling (payment intent id), and reservation status checks (PAID/CONFIRMED already → no-op).
- **Distributed transaction failure:** Single-service design; cross-service flows (e.g. payment gateway) use webhooks and idempotent handling. For multi-service rollback, extend with saga/outbox pattern (not in MVP).
- **Payment gateway timeout:** Create PaymentIntent is synchronous; if gateway times out, reservation can stay PAYMENT_PROCESSING. Optional: scheduled job to mark PAYMENT_PROCESSING as CANCELLED after X minutes (configurable); currently manual or retry from client.
- **Booking consistency errors:** `FraudDetectionService.runConsistencyCheck()`:
  - **Orphan reservation:** CONFIRMED/PAID reservation for a trip in the past with no trip segment using that reservation → audit `FRAUD_CONSISTENCY_ORPHAN`.
  - **Refunded ticket on segment:** Segment has `reservationId` but that reservation is REFUNDED → audit `FRAUD_CONSISTENCY_REFUNDED_TICKET`.

**Scheduled run:** All checks (ticket sharing, refund fraud, multi-device, suspicious account, consistency) run on `booking.fraud.cron` (default every hour at :30). Chargeback is event-driven via webhook.

**Admin:** `POST /api/admin/fraud-detection/run` returns `{ ticketSharingAlerts, refundFraudAlerts, multipleDeviceAlerts, suspiciousAccountAlerts, consistencyAlerts, runAt }`.

---

## 1. Geofencing Engine

**Purpose:** Detect when users enter or exit platforms/stations (geographic zones).

- **Tech:** Java Spring Boot; geofence polygons (optional) or circles (radius) per station; `Geofence.polygonGeoJson` for point-in-polygon when set.
- **Flow:** Mobile (or admin simulation) sends GPS via `POST /api/location` → `LocationService.reportLocation()` → for each geofence, `isInside(lat, lon)` → on transition outside→inside: **ENTERED**; inside→outside: **EXITED** → `GeofenceService.recordEvent()` persists and publishes to event stream.
- **MVP:** Receive location updates → check inside geofence → log events (e.g. `geofence_events` table) → trigger alerts (e.g. Drools no-ticket rule, journey reconstruction).
- **Config:** `booking.geofence.gps-min-accuracy-meters`, `debounce-enter-ms`, calibration; see [GEOFENCE-ADMIN.md](GEOFENCE-ADMIN.md).

---

## 2. Journey Reconstruction

**Purpose:** Rebuild journeys from geofence events and ticket data.

- **Flow:** Geofence ENTERED events (per user) → paired with last EXITED (origin) → create **trip segment** (origin → destination, times) → match to schedule (±5 min) → set confidence score → compare to user’s CONFIRMED/PAID tickets → set fare status (PAID / PENDING_RESOLUTION / UNPAID_TRAVEL).
- **Tech:** `TripSegmentService.onStationEntryDetected()` (called from pipeline on ENTERED); idempotency key; train match and confidence in `updateMatchAndConfidence()`.
- **MVP:** Sequence events per user → match to tickets (route order) → create journey object (segment) → display/log (admin trip segments, user “My journeys”).
- **Details:** [JOURNEY-RECONSTRUCTION.md](JOURNEY-RECONSTRUCTION.md).

---

## 3. Fraud Detection Rules (Drools)

**Purpose:** Detect fare evasion or anomalies at the moment of station entry and when segments are created.

- **Tech:** Drools rules in `geofence.drl`; facts in `GeofenceRuleFacts` (e.g. `UserEnteredStation`, `TicketSearchResult`, `NoTicketAtEntry`).
- **Flow:** On station ENTERED → `GeofenceRulesService.onStationEntry()` runs rules; if “no ticket at entry” fires → create `StationEntryAction` (PENDING_OPTION) and notify user (Buy / Ignore / Scan QR). Journey vs ticket is then enforced in `TripSegmentService` (segment fare status and resolution window).
- **MVP:** Check journey vs ticket → log or alert if invalid → simple console/email (here: in-app notification and audit; extend to email if needed).

---

## Summary

| Layer | Responsibility |
|-------|----------------|
| **Geofencing Engine** | Location → geofence check → ENTERED/EXITED events. |
| **Journey Reconstruction** | Events → segments → train match → confidence → ticket comparison → PAID / PENDING / UNPAID. |
| **Fraud Detection Rules (Drools)** | No-ticket-at-entry → options + notifications; segment-level fare status and penalty. |
| **Fraud Detection Engine** | Ticket sharing, refund fraud, multi-device, chargeback, suspicious account, consistency; audit and scheduled/on-demand run. |

Config for fraud and geofencing: `booking.fraud.*`, `booking.geofence.*`, `booking.journey.reconstruction.*` in `application.yml`.

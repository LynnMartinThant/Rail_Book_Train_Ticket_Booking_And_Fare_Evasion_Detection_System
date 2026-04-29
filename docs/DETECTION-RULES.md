# Detection Rules (Journey & Fraud)

The system detects the following rules for fare compliance and fraud. Each is logged to the audit trail and used to prevent GPS errors and enforce policy.

---

## Architecture: Location Events → Drools → Fraud Alert / Case Creation

The pipeline runs in five **rule layers** inside a single Drools session to keep high accuracy (90–95%) and low false positives:

1. **Location Events (GPS / Geofence)** — Geofence events (ENTERED/EXITED) are stored and fed into the pipeline.
2. **Journey Reconstruction Engine** — Layer 2 rules turn validated location events into `StationVisit` and then `Journey` (origin → destination, 1–120 min window).
3. **Facts Inserted Into Drools** — `LocationEvent`, `StationVisit`, `Journey`, `Ticket`, `TicketUsage`, `RiskScore` (and optionally `DeviceLogin`).
4. **Rule Layers**
   - **Layer 1 — Data Validation:** Ignore poor GPS (accuracy > 100 m), ignore duplicate location events (same user, same timestamp).
   - **Layer 2 — Journey Reconstruction:** Station ENTER/EXIT → `StationVisit`; EXIT at origin then ENTER at destination (1–120 min) → `Journey`.
   - **Layer 3 — Ticket Verification:** No ticket for journey → `FraudAlert(NO_TICKET)`; expired ticket → `FraudAlert(EXPIRED_TICKET)`.
   - **Layer 4 — Fraud Detection:** Over-travel, route violation, ticket sharing, multiple device → `FraudAlert(...)`.
   - **Layer 5 — Risk Scoring:** Each `FraudAlert` adds +20 to `RiskScore`; if score > 80 → insert `InvestigationCase`.
5. **Fraud Alert / Case Creation** — `FraudAlert` and `InvestigationCase` are logged to the audit trail; segments are created from `Journey` facts.

**Example flow:** Ticket Sheffield → Meadowhall, actual travel Sheffield → Leeds → Over-travel rule fires → risk score increased → fraud case (investigation) created.

Rules are defined in `journey-fraud.drl`; fact types in `JourneyDroolsFacts`. The pipeline is invoked from `LocationEventPipelineConsumer` when a user enters a station (after the legacy “no ticket at entry” warning).

---

## 1. No Ticket

**Rule:** Passenger with no valid ticket for the detected journey.

**How the system detects it:**
- **At station entry (geofence):** Drools rule fires when a user enters a station geofence and has no CONFIRMED ticket from that station. A `StationEntryAction` (PENDING_OPTION) is created and the user is offered: Buy ticket / Ignore / I have a ticket (scan QR).
- **At journey reconstruction:** When a trip segment is created (origin EXITED → destination ENTERED) and no reservation covers the segment **and** both origin and destination are on the configured route, the segment is marked `PENDING_RESOLUTION` and the audit action **NO_TICKET** is logged.

**Config:** `booking.route.station-order` (route order), `booking.fare-evasion.resolution-window-minutes`, `booking.fare-evasion.default-penalty-amount`.

---

## 2. Over Travel

**Rule:** Traveling beyond ticket destination (short ticket).

**How the system detects it:**
- When a segment is fully on the route but the passenger’s best ticket only covers part of it (e.g. ticket Leeds → Sheffield, journey Leeds → Meadowhall), the segment is marked **UNDERPAID** and the audit action **OVER_TRAVEL** is logged. `additionalFare` is the difference between full route fare and paid fare.

**Config:** `booking.route.station-order` (for coverage check).

---

## 3. Route Violation

**Rule:** Wrong train route (segment not on ticket route or stations not on configured route).

**How the system detects it:**
- When a trip segment is created and no reservation covers it **and** either origin or destination is **not** on the configured route (e.g. travelled on a different line), the segment is marked `PENDING_RESOLUTION` and the audit action **ROUTE_VIOLATION** is logged. This distinguishes “no ticket on this route” from “no ticket at all” (NO_TICKET).

**Config:** `booking.route.station-order`.

---

## 4. Ticket Sharing

**Rule:** Multiple users using one ticket (same reservation, different passengers, overlapping times).

**How the system detects it:**
- The fraud engine groups PAID segments by `reservationId`. If the same reservation appears for two or more different `passengerId`s with overlapping segment times, the audit action **TICKET_SHARING** is logged. Run on a schedule or via `POST /api/admin/fraud-detection/run`.

**Config:** `booking.fraud.ticket-sharing-cron` (default: hourly).

---

## 5. Suspicious Pattern

**Rule:** Abnormal travel behavior.

**How the system detects it:**
- **Suspicious account:** User with high fare evasion count (FARE_EVASION audit entries ≥ threshold) or many PENDING_RESOLUTION segments (≥ threshold). The audit action **SUSPICIOUS_PATTERN** is logged with details (e.g. `fareEvasionCount`, `pendingResolutionSegments`).
- **Multiple device / impossible journey:** Same user, two segments with overlapping time and different origin/destination (delegated to fraud engine; can be considered part of suspicious pattern).

**Config:** `booking.fraud.suspicious-account-min-evasion-count`, `booking.fraud.suspicious-account-min-pending-resolution`.

---

## 6. Low Confidence

**Rule:** Prevent GPS errors by not triggering penalty/notification when journey reconstruction confidence is low.

**How the system detects it:**
- Each segment gets a **confidence score** (0–100) from GPS accuracy, station match, train match (±5 min), and duration. When a PENDING_RESOLUTION segment becomes overdue and is moved to UNPAID_TRAVEL with a penalty:
  - If **confidence ≥ threshold** (e.g. 85%): penalty is applied and the user is notified.
  - If **confidence < threshold**: penalty is recorded in the segment but **no user notification** is sent, and the audit action **LOW_CONFIDENCE** is logged. This avoids penalising users for bad GPS or mis-detection.

**Config:** `booking.journey.reconstruction.confidence-threshold` (default: 85).

---

## Audit action names

These constants are used in the audit log and in the codebase (`DetectionRules`):

| Rule              | Audit action       | When logged |
|-------------------|--------------------|-------------|
| No Ticket         | `NO_TICKET`        | Segment created, no ticket, both stations on route |
| Over Travel       | `OVER_TRAVEL`      | Segment created, UNDERPAID (travel beyond ticket dest) |
| Route Violation   | `ROUTE_VIOLATION`  | Segment created, no ticket, at least one station off route |
| Ticket Sharing    | `TICKET_SHARING`   | Fraud run: same reservation, different passengers, overlapping times |
| Suspicious Pattern| `SUSPICIOUS_PATTERN` | Fraud run: high evasion count or many PENDING_RESOLUTION |
| Low Confidence    | `LOW_CONFIDENCE`   | Overdue segment penalty recorded but notification suppressed |

For more on the fraud engine and journey reconstruction, see [FRAUD-AND-ENGINES.md](FRAUD-AND-ENGINES.md) and [JOURNEY-RECONSTRUCTION.md](JOURNEY-RECONSTRUCTION.md).

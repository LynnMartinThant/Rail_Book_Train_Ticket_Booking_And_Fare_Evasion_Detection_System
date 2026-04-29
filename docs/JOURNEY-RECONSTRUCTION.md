# Journey Reconstruction Algorithm & Accuracy Techniques

This document describes the step-by-step journey reconstruction algorithm and the accuracy improvement techniques implemented to reach 90–95% detection accuracy.

---

## Algorithm (Steps 1–10)

### Step 1 — Collect Passenger Location Events 📍

The mobile app sends GPS events (e.g. every 15s) to `POST /api/location`.

**Request body:** `{ "latitude", "longitude", "accuracyMeters" }` (accuracy optional).

**Implementation:** `LocationService.reportLocation(userId, lat, lon, accuracyMeters)` persists the latest position and uses accuracy for confidence scoring (Step 8). Low-accuracy points can be filtered by `booking.geofence.gps-min-accuracy-meters` so they are not used for geofence events (GPS noise filtering).

---

### Step 2 — Geofence Detection

Each location is checked against every station geofence (circle or polygon).

**Logic:** For each geofence, `isInside(lat, lon, geofence)` → if transition from outside to inside: create **STATION_ENTRY** (ENTERED); inside to outside: **STATION_EXIT** (EXITED).

**Implementation:** `LocationService.reportLocation` → `GeofenceService.recordEvent(userId, geofenceId, ENTERED|EXITED, null, accuracyMeters)`. Events stored in `geofence_events` with optional `accuracy_meters`. Polygon boundaries supported via `Geofence.polygonGeoJson` (see [GEOFENCE-ADMIN.md](GEOFENCE-ADMIN.md)).

---

### Step 3 — Event Timeline Construction ⏱️

Events are stored with `created_at` and sorted chronologically per user.

**Implementation:** `GeofenceEventRepository` (e.g. `findByUserIdOrderByCreatedAtDesc`, `findExitedEventsByUserIdOrderByCreatedAtDesc`). The pipeline processes ENTERED events and pairs them with the most recent EXITED to form segments. Timeline = ordered list of ENTERED/EXITED events per user.

---

### Step 4 — Station Transition Detection 🚉

Segment created when: **last EXIT at station A** and **current ENTER at station B**, with **time difference ≤ maxTravelTime**.

**Algorithm:**
```
IF station_A (EXITED) detected
AND station_B (ENTERED) detected
AND time difference < maxTravelTimeMinutes
THEN create journey segment A → B
```

**Implementation:** `TripSegmentService.onStationEntryDetected`. Iterates over recent EXITED events; for each origin ≠ destination and `segmentStartTime` before `entryTime`, checks `ChronoUnit.MINUTES.between(segmentStartTime, entryTime) <= maxTravelTimeMinutes` (config: `booking.journey.reconstruction.max-travel-time-minutes`, default 120). Idempotency key prevents duplicate segments.

---

### Step 5 — Train Matching Algorithm 🚆

Match the segment to a train from the schedule (departure within ±5 min of segment start).

**Logic:**
```
FOR each trip (fromStation, toStation) in schedule
  IF segment.originStation = fromStation AND segment.destinationStation = toStation
  AND trip.departureTime within [segmentStart - 5min, segmentStart + 5min]
  THEN assign segment.matchedTripId = trip.id
```

**Implementation:** `TripSegmentService.updateMatchAndConfidence` → `TripRepository.findByFromStationAndToStationAndDepartureTimeBetween(origin, dest, windowStart, windowEnd)` with window = ±`train-match-window-minutes` (default 5). First match is stored on `TripSegment.matchedTripId`.

---

### Step 6 — Journey Path Reconstruction

Each segment is a leg (origin → destination). Full journey = ordered list of segments for the passenger.

**Implementation:** `trip_segments` table: `passenger_id`, `origin_station`, `destination_station`, `origin_platform`, `destination_platform`, `segment_start_time`, `segment_end_time`, `matched_trip_id`. Admin and user APIs return segments; a full journey is the chronological list of segments for that user (e.g. Sheffield → Meadowhall, Meadowhall → Leeds).

---

### Step 7 — Ticket Comparison 🎫

Compare reconstructed segment to the passenger’s tickets (CONFIRMED/PAID reservations).

**Logic:** If `actualDestination` beyond `ticketDestination` on the route → flag **OVER_TRAVEL** (UNDERPAID or PENDING_RESOLUTION). If no ticket covers the segment → **no-ticket** (PENDING_RESOLUTION then UNPAID_TRAVEL).

**Implementation:** `TripSegmentService.buildSegment` uses `RouteOrderConfig.ticketCoversSegment(tFrom, tTo, originStation, destinationStation)`. Covers PAID, UNDERPAID, PENDING_RESOLUTION, UNPAID_TRAVEL.

---

### Step 8 — Confidence Scoring (Key to High Accuracy)

Confidence score 0–100 based on four factors:

| Factor | Weight | Implementation |
|--------|--------|----------------|
| GPS accuracy | 25% | Average of origin/destination event `accuracy_meters`. &lt;20 m → 100%, 20–50 → 80%, 50–100 → 60%, &gt;100 → 40%. Null → 50%. |
| Station detection | 25% | 100% (both origin and destination detected). |
| Train timetable match | 30% | 100% if `matchedTripId` set, 0% otherwise. |
| Travel duration consistency | 20% | 100% if segment duration 5–120 min, else 50%. |

**Formula:** `confidence = gps×0.25 + station×0.25 + train×0.30 + duration×0.20`. Stored in `TripSegment.confidence_score`.

**Implementation:** `TripSegmentService.updateMatchAndConfidence`, `gpsConfidenceScore(accuracyOrigin, accuracyDest)`.

---

### Step 9 — Fraud Detection Rule

Only trigger alert/enforcement if **confidence ≥ threshold** (default 85%).

**Rule:** If `confidence > 85%` AND (no ticket OR over-travel) THEN create fraud alert / apply penalty. If confidence &lt; 85%, segment is still marked UNPAID_TRAVEL and penalty amount is recorded, but **no penalty notification** is sent (audit log notes “low confidence”).

**Implementation:** `TripSegmentService.processOverdueResolutions`: when applying penalty, checks `segment.getConfidenceScore() >= confidenceThreshold`; only then sends penalty notification and full audit. Otherwise logs “low confidence, penalty recorded but no notification”.

---

### Step 10 — Alert / Enforcement

**Actions:** Send notification, log event (audit_log FARE_EVASION), apply penalty (amount on segment, UserNotification). Optionally notify railway authority via audit or external integration.

**Implementation:** `AuditLogService.log(..., FARE_EVASION_ACTION, ...)`, `UserNotificationRepository.save(penalty message)`, `TripSegment.penaltyAmount`, `TripSegment.fareStatus = UNPAID_TRAVEL`. Penalty only sent when confidence ≥ threshold (Step 9).

---

## Accuracy Improvement Techniques (1–10)

| # | Technique | Implementation |
|---|-----------|----------------|
| 1 | **GPS filtering** | `booking.geofence.gps-min-accuracy-meters`: reject location updates with worse accuracy for event generation. |
| 2 | **Station dwell-time verification** | Configurable: require minimum time inside geofence before ENTERED (future: dwell-time threshold). Currently debounce reduces duplicate ENTERED. |
| 3 | **Train schedule matching** | Step 5: ±5 min window; `matched_trip_id` on segment. |
| 4 | **Multiple geofence confirmations** | Polygon boundaries and/or circle; debounce (`booking.geofence.debounce-enter-ms`) avoids single-point jitter. |
| 5 | **Time window tolerance (±5 min)** | `booking.journey.reconstruction.train-match-window-minutes: 5`. |
| 6 | **Confidence scoring** | Step 8: 0–100 score; only enforce when ≥ 85%. |
| 7 | **Historical passenger patterns** | Not implemented (optional future: baseline behaviour per user). |
| 8 | **Noise filtering for GPS drift** | GPS accuracy filter + debounce; low-accuracy points not used for events. |
| 9 | **Route-based movement detection** | Segment only created for valid origin→destination pairs; route order used for ticket coverage (same route). |
| 10 | **Fallback interpolation for missing events** | Not implemented (optional: infer EXIT at midpoint if ENTER A then ENTER B without EXIT A). |

---

## Configuration Summary

```yaml
booking:
  geofence:
    gps-min-accuracy-meters: 50   # 0 = no filter
    debounce-enter-ms: 15000
  journey:
    reconstruction:
      train-match-window-minutes: 5
      confidence-threshold: 85
      max-travel-time-minutes: 120
```

---

## API / Data

- **POST /api/location** — Body may include `accuracyMeters` (optional). Used for confidence.
- **GET /api/my/trip-segments** — Returns segments with `confidenceScore`, `matchedTripId`.
- **GET /api/admin/trip-segments**, **GET /api/admin/fare-evasion-cases** — Same fields for admin.
- **Geofence events** — `geofence_events.accuracy_meters` stored when provided.
- **Trip segments** — `trip_segments.confidence_score`, `trip_segments.matched_trip_id`.

---

## References

- [ARCHITECTURE.md](../ARCHITECTURE.md) — Event pipeline, Drools, TripSegmentService.
- [docs/GEOFENCE-ADMIN.md](GEOFENCE-ADMIN.md) — Geofence polygon, GPS filter, debounce.
- [docs/SYSTEM-SPEC.md](SYSTEM-SPEC.md) — System spec and implementation mapping.

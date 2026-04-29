# Railway Passenger Monitoring – System Specification & Implementation Mapping

This document maps the **target system specification** (passenger monitoring, fraud detection, journey reconstruction) to the **current implementation** in the RailBook codebase.

---

## System Aims

| Aim | Implementation |
|-----|----------------|
| **Detect passengers traveling without tickets** | Drools rule in `geofence.drl`: on station entry, check if user has CONFIRMED/PAID reservation for that station → if not, create `StationEntryAction` (PENDING_OPTION) and send notification (Buy / Ignore / Scan QR). `TripSegmentService` marks segment as PENDING_RESOLUTION or UNPAID_TRAVEL when no ticket covers origin→destination. |
| **Detect passengers traveling beyond ticket destination** | `TripSegmentService.buildSegment`: compares segment (originStation → destinationStation) to user’s CONFIRMED reservations via `RouteOrderConfig.ticketCoversSegment(tFrom, tTo, originStation, destinationStation)`. If journey extends beyond ticket destination → UNDERPAID or PENDING_RESOLUTION. |
| **Reconstruct passenger journeys** | `TripSegmentService.onStationEntryDetected`: on ENTER at destination, finds last EXIT at origin; creates `TripSegment` with origin/destination stations, platforms, start/end times. Idempotency key prevents duplicate segments. Timeline = ordered geofence events + segments. |
| **Monitor railway passenger movement patterns** | Admin dashboard: geofence events, user locations, trip segments, fare evasion cases. `GET /api/admin/geofence-events`, `GET /api/admin/trip-segments`, `GET /api/admin/fare-evasion-cases`. Enterprise KPIs in `AdminMetricsService` (e.g. total geofence events, fare evasion metrics). |

---

## Core Modules

### 1. Booking Lifecycle Management

| Function | Implementation |
|----------|----------------|
| Ticket purchase | `BookingService`: reserve → payment → confirm. Reservation (CONFIRMED) = ticket. |
| Seat reservation | `TripSeat` + pessimistic lock; `Reservation` with status (RESERVED → PAID → CONFIRMED). |
| Dynamic pricing | `PricingService.getPrice(trip, now)`: fare buckets + Drools `pricing.drl` (advance/off-peak/occupancy). |
| Payment processing | `PaymentGatewayService` (Stripe); webhook updates reservation to PAID/CONFIRMED or CANCELLED. |

**Technologies:** Spring Boot, Stripe, Drools (pricing).

---

### 2. Geofencing Engine

| Function | Implementation |
|----------|----------------|
| Station geofence detection | `LocationService.reportLocation`: for each `Geofence`, evaluates inside circle or point-in-polygon (`polygonGeoJson`); emits ENTERED/EXITED via `GeofenceService.recordEvent`. |
| Platform presence detection | Each `Geofence` has optional `platform` (e.g. 1A, 3B). Trip segments store `originPlatform`, `destinationPlatform` from the geofences at exit/enter. |
| Location event generation | `GeofenceEvent` (user_id, geofence_id, event_type ENTERED/EXITED, created_at). Published to `LocationEventStream` (Kafka or in-process). |

**Technologies:** Custom point-in-polygon and haversine circle; optional GeoTools/JTS can be added for PostGIS-style geometry. See [docs/GEOFENCE-ADMIN.md](GEOFENCE-ADMIN.md).

---

### 3. Journey Reconstruction Engine

| Function | Implementation |
|----------|----------------|
| Event timeline processing | `GeofenceEventRepository`: events ordered by `created_at`. Pipeline consumes ENTERED events; pairs with last EXITED to form segment. |
| Station transition detection | `TripSegmentService.onStationEntryDetected`: last EXIT (origin) + current ENTER (destination) → one segment; same-station filtered out. |
| Train schedule matching | Segment fare from `TripRepository.findByFromStationAndToStation`. Ticket coverage uses `booking.route.station-order` (route order). Departures: `GET /api/stations/{stationName}/departures`. |

**Technologies:** Apache Kafka (optional): `LocationEventStream` publishes to topic; `KafkaLocationEventConsumer` invokes same pipeline as in-process events.

---

### 4. Fraud Detection Engine

| Function | Implementation |
|----------|----------------|
| Over-travel detection | `RouteOrderConfig.ticketCoversSegment`: ticket (from, to) covers segment (origin, dest) only if segment lies within [from, to] on route order. Beyond destination → UNDERPAID / PENDING_RESOLUTION. |
| No-ticket detection | Drools `GeofenceRulesService.onStationEntry` + `TripSegmentService.buildSegment`: no CONFIRMED reservation covering segment → PENDING_RESOLUTION then UNPAID_TRAVEL after resolution window. |
| Ticket sharing detection | Implemented: same reservation used by different passengers with overlapping journey times → `detectTicketSharing()` logs TICKET_SHARING; scheduled hourly; `POST /api/admin/detect-ticket-sharing`. |

**Technologies:** Drools (`geofence.drl`, `pricing.drl`), `TripSegmentService`, `AuditLogService` (FARE_EVASION), `UserNotification`.

---

## Database Design (Spec → Implementation Mapping)

| Spec table | Implementation table(s) | Description |
|------------|-------------------------|-------------|
| **users** | `users` (Auth) | Passenger account (email, password hash). `X-User-Id` used as passenger id in booking/geofence. |
| **tickets** | `reservations` + `trip_seats` + `trips` | Ticket = CONFIRMED reservation linked to trip/seat; PDF ticket via `TicketPdfService`. |
| **stations** | `geofences` | Station geofence data: name, station_name, platform, lat/lon, radius_meters, optional polygon_geojson. |
| **location_events** | `geofence_events` | GPS-driven ENTERED/EXITED events; `user_locations` stores latest position per user. |
| **journeys** | `trip_segments` | Reconstructed journeys: passenger_id, origin/destination station and platform, start/end time, fare_status, paid_fare, penalty. |
| **fraud_alerts** | `audit_log` (actions FARE_EVASION, TICKET_SHARING), `ticket_alerts`, `station_entry_actions`, `user_notifications` | Detected violations: audit log (fare evasion, ticket sharing), ticket alerts (admin-sent), station entry actions (no ticket at entry), penalty notifications. |

**Database:** H2 (file) or PostgreSQL. PostGIS can be added for spatial indexes and advanced geometry; current implementation uses JPA with lat/lon and optional polygon JSON.

---

## System Architecture (Spec-Aligned)

```
Mobile App (React / Capacitor)
   ↓
API Gateway (BookingController /api)
   ↓
Event streaming (Kafka or in-process Spring events)
   ↓
Geofence engine (LocationService → GeofenceService → LocationEventStream)
   ↓
Journey reconstruction (LocationEventPipelineConsumer → TripSegmentService)
   ↓
Fraud detection (GeofenceRulesService [Drools] + TripSegmentService fare status)
   ↓
Database (PostgreSQL or H2)
```

- **API Gateway:** Single service exposes `/api` (CORS in `WebMvcConfig`). No separate API Gateway product; same process.
- **Event streaming:** `LocationEventStream.publish(GeofenceEventMessage)` → Kafka topic if configured, else `ApplicationEventPublisher` (GeofenceEventRecordedEvent).
- **Geofence engine:** Inside booking-service: `LocationService`, `GeofenceService`, `GeofenceRepository`.
- **Journey reconstruction:** `LocationEventPipelineConsumer.handle()` → `TripSegmentService.onStationEntryDetected()`.
- **Fraud detection:** Drools rules + segment vs ticket comparison; penalty and notifications.

---

## Functional Requirements

| Requirement | Status | Implementation |
|-------------|--------|----------------|
| Detect passenger location events | ✔ | `POST /api/location` (or app reporting); `UserLocation` updated; geofence evaluation in `LocationService.reportLocation`. |
| Detect station entry and exit | ✔ | ENTERED/EXITED in `GeofenceEvent`; circle or polygon containment in `LocationService`. |
| Reconstruct passenger journeys | ✔ | `TripSegment` from EXIT→ENTER pairs; idempotency; platform and times stored. |
| Match journeys with ticket data | ✔ | `TripSegmentService.buildSegment`: `RouteOrderConfig.ticketCoversSegment`; fare_status PAID / UNDERPAID / PENDING_RESOLUTION / UNPAID_TRAVEL. |
| Detect fare evasion scenarios | ✔ | No ticket at entry (Drools); segment without covering ticket (TripSegmentService); over-travel (route order). |
| Generate fraud alerts | ✔ | `StationEntryAction`, `UserNotification`, `AuditLog` (FARE_EVASION), `TicketAlert` (admin). |
| Store journey history | ✔ | `trip_segments` table; `GET /api/my/trip-segments`; admin list. |
| Provide monitoring dashboard | ✔ | Admin UI: geofence map, events, trip segments, fare evasion cases, system health, false positive monitoring. |

---

## Non-Functional Requirements

| Requirement | Target | Implementation / notes |
|-------------|--------|-------------------------|
| **Scalability** | Handle thousands of passenger events | Single service; Kafka allows horizontal scaling of consumers; DB connection pool; idempotent segment processing. For very high throughput, add partitioning and scale pipeline consumers. |
| **Accuracy** | ≥90% detection accuracy | Polygon geofences, GPS noise filter (`gps-min-accuracy-meters`), event debouncing reduce false positives; route-order and CONFIRMED-only ticket matching. Accuracy can be measured via false positive dashboard and calibration. |
| **Reliability** | Continuous event processing | Kafka consumer group; in-process fallback when Kafka unset; transactional event persist + publish; idempotency keys for segments. |
| **Latency** | Fraud detection within 5 seconds | Pipeline runs synchronously (in-process) or on Kafka consume; Drools + segment build are in-memory after event persisted. Sub-second typical; ensure Kafka lag and DB latency stay within 5 s. |
| **Security** | Encrypted passenger data | HTTPS in production; Stripe handles payment data; DB credentials via env. Add encryption-at-rest and PII handling per policy; JWT for API auth in production. |

---

## Optional Enhancements (Spec vs Current)

| Spec / common need | Current state | Possible addition |
|--------------------|---------------|-------------------|
| GeoTools / JTS | Custom point-in-polygon and circle | Add GeoTools/JTS for complex polygons and spatial indexes; or PostGIS for DB-side geometry. |
| PostGIS | H2/PostgreSQL with lat/lon + JSON polygon | Add PostGIS extension and geometry columns for spatial queries and indexing. |
| Ticket sharing detection | ✔ Implemented | `TripSegment.reservationId` set when PAID; `TripSegmentService.detectTicketSharing()` finds same reservation used by different passengers with overlapping segment times; logs `TICKET_SHARING` to audit; scheduled hourly (`booking.fraud.ticket-sharing-cron`); admin trigger `POST /api/admin/detect-ticket-sharing`. |
| Dedicated “tickets” table | Reservations = tickets | Optional view or table that flattens CONFIRMED reservation + trip + seat as “ticket” for reporting. |

---

## References

- [ARCHITECTURE.md](../ARCHITECTURE.md) – High-level architecture, event pipeline, dynamic pricing.
- [docs/GEOFENCE-ADMIN.md](GEOFENCE-ADMIN.md) – Geofence admin: polygon, platform, GPS filter, debounce, calibration, health.
- [docs/JOURNEY-RECONSTRUCTION.md](JOURNEY-RECONSTRUCTION.md) – Journey reconstruction algorithm (steps 1–10) and accuracy techniques.
- [docs/ENTERPRISE-METRICS.md](ENTERPRISE-METRICS.md) – KPIs and deployment checklist.

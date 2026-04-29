# RailBook – Architecture

**System specification mapping:** For a concise mapping of this codebase to the railway passenger monitoring spec (detect no-ticket/over-travel, reconstruct journeys, fraud detection, NFRs), see [docs/SYSTEM-SPEC.md](docs/SYSTEM-SPEC.md).

## High-level architecture (enterprise view)

```
                +-------------------+
                |  Web Application  |
                +---------+---------+
                          │
                          ▼
                +-------------------+
                |  API Gateway      |
                +---------+---------+
                          │
      ┌───────────────────┼
      ▼                   ▼                   
+-------------+    +--------------+    
| Booking     |    | Pricing      |   
| Service     |    | Engine       |    
+-------------+    +--------------+    
      │                   │
      ▼                   ▼
+-------------+    +--------------+
| Seat        |    | Rule Engine  |
| Inventory   |    | (Drools)     |
+-------------+    +--------------+
      │
      ▼
+-------------+
| Payment     |
| Service     |
+-------------+
      │
      ▼
+-------------+
| Ticket      |
| Service     |
+-------------+
      │
      ▼
+-------------+
| Validation  |
| System      |
+-------------+
```

### Mapping to current implementation

All components above are implemented within the **booking-service** (single deployable). The API Gateway is the same origin (Vercel proxy or direct `/api`). Mobile App = React web or Capacitor (Android/iOS).

| Box in diagram | Implementation in codebase |
|----------------|----------------------------|
| **Mobile App** | React app (`src/`); Capacitor for Android/iOS. Calls `/api` (or `REACT_APP_API_URL`). |
| **API Gateway** | Single entry: `BookingController` (base path `/api`). CORS in `WebMvcConfig`. |
| **Booking Service** | `BookingService`: reserve → payment → confirm; `TripQueryService`: list trips. |
| **Pricing Engine** | `PricingService.getPrice(trip, now)` using fare buckets + Drools. |
| **Journey Planner** | `TripSegmentService`: origin/destination from geofence ENTER/EXIT; trip segments, departures. |
| **Seat Inventory** | `TripSeat` + `Reservation`; `TripSeatRepository.findByTripIdAndSeatIdForUpdate` (pessimistic lock). |
| **Rule Engine (Drools)** | Pricing: `rules/pricing.drl`. Geofence: `rules/geofence.drl` (no ticket at entry → options). |
| **Payment Service** | `PaymentGatewayService` (Stripe); webhook sets PAID/CONFIRMED or CANCELLED on failure. |
| **Ticket Service** | `TicketPdfService`; QR in PDF; `BookingController` ticket PDF and QR endpoints. |
| **Validation System** | Geofence: `GeofenceRulesService` (ticket check, StationEntryAction); `TripSegmentService` (fare status); QR validate `validateQrAndCompleteAction`. |

See **Enterprise metrics and checklist** below and `docs/ENTERPRISE-METRICS.md` for KPIs and validation checklist before deploying to Render/Vercel.

---

## Event pipeline (GPS → Drools → Violation → Penalty)

```
Mobile App (GPS + Geofence)
   │
   ▼
Location Event Service  (reportLocation / recordEvent → persist + publish)
   │
   ▼
Event Stream  (Kafka topic "location-events" or in-process Spring events)
   │
   ▼
Drools Rule Engine
   │
   ├── Warning System      → No ticket at entry: StationEntryAction + user options (Buy / Ignore / Scan QR)
   │
   ├── Travel Detection   → Trip segment (origin → destination) from exit/enter sequence
   │
   ▼
Violation Detection       → Fare status: PAID / UNDERPAID / UNPAID_TRAVEL
   │
   ▼
Admin Dashboard           → Geofence events, trip segments, fare evasion audit
   │
   ▼
Penalty Issuing System    → UserNotification + AuditLog for UNPAID_TRAVEL
```

### Components

| Layer | Implementation |
|-------|----------------|
| **Mobile App** | Web app or native: reports GPS; backend detects geofence ENTERED/EXITED. |
| **Location Event Service** | `LocationService.reportLocation`, `GeofenceService.recordEvent`: persist event, then `LocationEventStream.publish()`. |
| **Event Stream** | Kafka topic `location-events` when `spring.kafka.bootstrap-servers` is set; otherwise in-process `GeofenceEventRecordedEvent`. |
| **Pipeline consumer** | `LocationEventPipelineConsumer` (Spring event) and `KafkaLocationEventConsumer` (Kafka): run Drools + Travel + Violation. |
| **Drools / Warning** | `GeofenceRulesService.onStationEntry`: ticket check; if no ticket → `StationEntryAction` + user notification with options. |
| **Travel Detection** | `TripSegmentService.onStationEntryDetected`: build segment from last EXIT to current ENTER; idempotent by segment key. |
| **Violation Detection** | Inside `TripSegmentService`: compare segment to CONFIRMED reservations → PAID / UNDERPAID / UNPAID_TRAVEL. |
| **Admin Dashboard** | Admin UI: geofences, events, trip segments, fare evasion audit, send notifications. |
| **Penalty Issuing** | For UNPAID_TRAVEL: `UserNotification` (penalty message) + `AuditLog` (FARE_EVASION). `TripSegmentEventListener` logs. |

### Config

- **Kafka (optional)**  
  - Set `spring.kafka.bootstrap-servers` (e.g. `KAFKA_BOOTSTRAP_SERVERS=localhost:9092`) to use Kafka.  
  - If unset, the pipeline runs with in-process Spring events only.

- **Geofences**  
  - Seeded or created via admin; `LocationService` evaluates ENTER/EXIT from reported coordinates.

### Payment flow

- **Reservation states**: PENDING_PAYMENT → PAYMENT_PROCESSING → PAID → CONFIRMED (or CANCELLED).  
- **Gateway**: Stripe (Visa, Apple Pay); server verifies via webhook only.  
- **Geofence/travel check**: Only CONFIRMED (or PAID) reservations count as valid tickets for segment validation.

---

## Dynamic pricing (fare buckets + Drools)

### Flow

```
User search (from / to)
   │
   ▼
TripQueryService.listTrips()
   │
   ▼
For each trip: PricingService.getPrice(trip, now)
   │
   ├── Load fare buckets (ADVANCE_1, ADVANCE_2, ADVANCE_3, OFF_PEAK, ANYTIME)
   ├── Seat availability: count CONFIRMED/PAID reservations vs total seats
   ├── Build PricingContext (occupancy, peak, weekend, days until departure)
   ▼
Drools pricing rules (rules/pricing.drl)
   │
   ├── Advance not available same day
   ├── Off-peak not in peak hours (06:30–09:30, 16:00–19:00)
   ├── Close lowest tier at 70% occupancy; more at 80%
   ├── Last-minute: &lt;5 seats left → only Anytime
   ▼
Select lowest available tier → price + fareTier in TripDto
   │
   ▼
Reserve: same price from PricingService used for reservation.amount
```

### Data

| Data | Description |
|------|--------------|
| **Fare buckets** | `fare_buckets`: per trip, tier name, seats allocated (-1 = unlimited), price, display order. Seeded per trip (e.g. Advance 1–3, Off-Peak, Anytime). |
| **Seat inventory** | Total seats from `trip_seats`; seats sold = count of reservations (CONFIRMED, PAID) per trip. |
| **Pricing rules** | Drools DRL in `src/main/resources/rules/pricing.drl`; change rules without changing app code. |

### Behaviour

- **Booking time**: Advance tiers disabled when `daysUntilDeparture < 1` (same-day or after departure).
- **Peak/off-peak**: Departure in 06:30–09:30 or 16:00–19:00 → Off-peak tier disabled; Anytime always available.
- **Occupancy**: At 70% sold, Advance 1 closes; at 80%, Advance 2 also closes.
- **Last-minute**: When fewer than 5 seats left, only Anytime tier is offered.
- **Weekend**: Off-peak gets a 5% discount when departure is Saturday/Sunday.

---

## Enterprise metrics and checklist

The system is designed to meet enterprise-level KPIs and a full demonstration checklist before deployment (Render + Vercel).

| Area | Target | Implementation |
|------|--------|----------------|
| **Booking** | Success ≥ 99%, double booking 0%, latency < 2 s | Pessimistic lock on seat; ACID reserve → payment → confirm; expiry scheduler. |
| **Fare evasion** | Detection ≥ 95%, validation < 1 s | Drools geofence rules; segment vs CONFIRMED tickets; QR validate. |
| **Reliability** | Uptime ≥ 99.9%, API < 500 ms, consistency 100% | Single service, JPA transactions; measure via deployment and APM. |
| **Concurrency** | 1000+ users, 200–500 bookings/s | DB pool + locking; validate with load tests. |
| **Security** | No unauthorized access, no duplicate payment | Stripe webhook verification; add auth (JWT) for production. |

**Checklist (all implemented):**

- ✔ Booking flow: search → list trips (dynamic pricing) → seat inventory → reserve → payment → ticket.
- ✔ Double booking prevention: pessimistic lock; one succeeds, other gets "Seat unavailable".
- ✔ Payment failure: webhook sets CANCELLED; seat released.
- ✔ Reservation timeout: configurable minutes; scheduled job marks EXPIRED; seat returned to inventory.
- ✔ Geofence fare evasion: valid ticket → allow; no ticket → alert + options (Buy / Ignore / Scan QR).
- ✔ Concurrency: safe under load; run stress test (e.g. 1000 requests) to confirm latency.

**Full details:** See [docs/ENTERPRISE-METRICS.md](docs/ENTERPRISE-METRICS.md) for the complete metric table, formulas, and verification steps before deploying to Render and Vercel.

---

## Core modules (spec-aligned)

The system implements the four core modules of the railway passenger monitoring spec:

| Module | Role | Implementation |
|--------|------|----------------|
| **Booking Lifecycle** | Ticket purchase, seat reservation, dynamic pricing, payment | `BookingService`, `PricingService` (Drools), `PaymentGatewayService`, reservations/trips/seats |
| **Geofencing Engine** | Station/platform geofence detection, location event generation | `LocationService` (circle + polygon), `GeofenceService`, `GeofenceEvent` |
| **Journey Reconstruction** | Event timeline, station transitions, schedule matching | `TripSegmentService`, `LocationEventPipelineConsumer`, Kafka or in-process events |
| **Fraud Detection** | No-ticket, over-travel detection; fraud alerts | Drools `geofence.drl`, `TripSegmentService` (fare status), `AuditLog` / `UserNotification` |

Database mapping (users, tickets, stations, location_events, journeys, fraud_alerts), functional and non-functional requirements, and optional enhancements (GeoTools/JTS, PostGIS, ticket-sharing) are in [docs/SYSTEM-SPEC.md](docs/SYSTEM-SPEC.md).

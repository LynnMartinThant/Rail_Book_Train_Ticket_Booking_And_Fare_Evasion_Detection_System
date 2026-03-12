# RailBook – Geofence & Payment Architecture

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

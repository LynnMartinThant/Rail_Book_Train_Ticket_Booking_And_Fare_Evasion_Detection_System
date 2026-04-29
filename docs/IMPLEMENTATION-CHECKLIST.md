# Implementation checklist — movement, journey, explainability

Maps each demonstrable criterion to **APIs**, **persistence**, **config**, and **automated tests**.

## 1. Booked journey consistency (passenger-facing)

| What | Where |
|------|--------|
| **Behaviour** | UI shows the **booked segment** (`journeyFromStation` → `journeyToStation`) when set on the reservation; else full trip `fromStation` → `toStation`. |
| **Backend** | `ReserveRequest` + `BookingService.reserve(..., journeyFromStation, journeyToStation)`; `ReservationDto` exposes journey fields. |
| **Frontend** | `Confirmation.js`, `Payment.js`, `MobileTicket.js`, `TicketsScreen.js`, `MyBookings.js`, `HomeScreen.js`; `api/client.js` `reserve(...)` sends journey in body. |
| **Manual demo** | Book with segment dropdowns → confirm payment/ticket screens show segment, not only train line. |

---

## 2. Ticket validation at station entry (journey origin)

| What | Where |
|------|--------|
| **Behaviour** | “Has ticket at entry” and QR completion use **journey origin** when set, else trip origin. |
| **Backend** | `GeofenceRulesService.onStationEntry` (reservation queries); `validateQrAndCompleteAction` compares `stationName` to ticket origin from journey or trip. |
| **Repository** | `ReservationRepository.existsByUserIdAndStatusAndJourneyFromStation(...)`. |
| **Tests** | `RailBookTestingMdIntegrationTest.whenUserValidatesQrWithConfirmedReservation_thenActionCompletes` (QR flow); extend with explicit journey-origin case if needed. |

---

## 3. Movement events from `/api/location`

| What | Where |
|------|--------|
| **Behaviour** | Each location report appends **`LocationReported`**; geofence transitions append **`GeofenceEntered`** / **`GeofenceExited`**. |
| **Backend** | `LocationService.reportLocation` → `MovementEventWriter.append`. |
| **Persistence** | Table **`movement_event`** (`MovementEventEntity`). |
| **Kafka (optional)** | Topic **`movement.events`** when `spring.kafka.bootstrap-servers` is set. |
| **Tests** | `MovementEventPipelineIntegrationTest` asserts `LocationReported` in stream for `/api/location`. |

---

## 4. Passenger movement read model

| What | Where |
|------|--------|
| **Behaviour** | Denormalised current movement per user (last station / last geofence transition). |
| **Persistence** | Table **`passenger_movement_view`** (`PassengerMovementView`). |
| **Projection** | `PassengerMovementProjectionConsumer` (in-process from `MovementEventRecordedEvent`). |
| **API** | `GET /api/admin/passenger-movement` (header `X-Admin-Secret`). |
| **Tests** | `MovementEventPipelineIntegrationTest` asserts projection row exists for test user. |

---

## 5. Emit `JourneySegmentConfirmed`

| What | Where |
|------|--------|
| **Behaviour** | After exit→enter style reconstruction, emit event with origin/destination and times. |
| **Implementation** | `MovementJourneyFallbackConsumer` and/or `MovementStreamsTopology` (when Streams enabled). |
| **Command** | `JourneySegmentCommandService.emitJourneySegmentConfirmed` → `MovementEventWriter`. |
| **Persistence** | Row in **`movement_event`** with type **`JourneySegmentConfirmed`**. |
| **Tests** | `MovementEventPipelineIntegrationTest` asserts event type in history. |

---

## 6. Emit `FareValidated`

| What | Where |
|------|--------|
| **Behaviour** | After segment creation from `JourneySegmentConfirmed`, run fare logic and append **`FareValidated`** with explainability payload. |
| **Implementation** | `MovementPolicyConsumer.handleJourneySegmentConfirmed` → `TripSegmentService.createSegmentFromJourney` → `MovementEventWriter` (`FareValidated`). |
| **Persistence** | **`movement_event`** + **`trip_segments`** (segment row). |
| **Tests** | `MovementEventPipelineIntegrationTest` asserts `FareValidated` appears. |

---

## 7. Standardised `explanation_json`

| What | Where |
|------|--------|
| **Schema** | JSON document on segment: `schemaVersion`, `policyEngine`, `fareDecision`, `reconstructionConfidence`, optional `disputeResolution`, `enforcement`. |
| **Backend** | `TripSegment.explanationJson`; built in `TripSegmentService.buildSegment` + merged in `updateMatchAndConfidence` / dispute / enforcement paths. |
| **Tests** | `MovementEventPipelineIntegrationTest.tripSegmentAdminApi_exposesStructuredExplanation` asserts `explanation` map via admin API. |

---

## 8. Explanation in DTOs and admin APIs

| What | Where |
|------|--------|
| **DTO** | `TripSegmentDto.explanation` (parsed from `explanationJson`). |
| **Mapping** | `BookingController.toTripSegmentDto(TripSegment)` uses `ObjectMapper`. |
| **Admin** | `GET /api/admin/trip-segments` (optional `passengerId`, `limit`) returns DTOs with `explanation`. |

---

## 9. Observability — metrics and health

| What | Where |
|------|--------|
| **Actuator** | `management.endpoints.web.exposure.include`: `health`, `info`, `metrics`, `prometheus`. |
| **Health** | `MovementPipelineHealthIndicator` → contributor **`movementPipeline`** on `/actuator/health`. Config: `booking.movement.stale-threshold-seconds`. |
| **Metrics** | `MovementPipelineMetrics`: append counts/duration, projection delay, policy stage counts/duration. |
| **Deps** | `spring-boot-starter-actuator` in `booking-service/pom.xml`. |

---

## 10. Dedupe — legacy vs event-driven reconstruction

| What | Where |
|------|--------|
| **Problem** | Legacy geofence pipeline (`JourneyDroolsPipelineService`) could duplicate work vs movement event pipeline. |
| **Switch** | `booking.movement.event-driven-pipeline-enabled` (default `false`). |
| **Behaviour** | When `true`, `LocationEventPipelineConsumer` still runs **`GeofenceRulesService.onStationEntry`** but **skips** `journeyDroolsPipelineService.runPipelineForUser`. |
| **Tests** | `LocationEventPipelineConsumerTest` verifies skip vs run. |

---

## 11. Fare coverage uses journey (not only trip)

| What | Where |
|------|--------|
| **Behaviour** | `TripSegmentService` uses `ticketFrom` / `ticketTo` (journey when set) for PAID / UNDERPAID / dispute checks. |
| **Route direction** | `RouteOrderConfig.ticketCoversSegment` supports forward and backward segments. |

---

## Recommended demo configuration

For a clean “movement intelligence” demo without duplicate segment creation from two pipelines:

```yaml
booking:
  movement:
    event-driven-pipeline-enabled: true   # dedupe: skip legacy journey Drools on ENTERED
    streams-enabled: false                # or true + Kafka for Streams
```

Optional Kafka:

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export BOOKING_MOVEMENT_STREAMS_ENABLED=true   # if using Kafka Streams topology
```

---

## Test commands

```bash
cd booking-service
mvn -q test-compile
mvn -q -Dtest=LocationEventPipelineConsumerTest,MovementEventPipelineIntegrationTest test
```

Full suite:

```bash
mvn -q test
```

---

## Quick API smoke (local)

Replace `ADMIN` with your `booking.admin.secret` and `USER` with a test user id.

```http
POST /api/location
X-User-Idalice
Content-Type application/json

{"latitude":53.38,"longitude":-1.47,"accuracyMeters":10}
```

```http
GET /api/admin/passenger-movement
X-Admin-Secretadmin123
```

```http
GET /api/admin/trip-segments?passengerId=alice&limit=50
X-Admin-Secretadmin123
```

```http
GET /actuator/health
GET /actuator/metrics
```

# Combined Test Strategy & Test Cases

This document defines a **combination of unit → integration → system → acceptance → performance → security → regression → compatibility → chaos** test cases for the RailBook system (React frontend + Spring Boot booking-service + H2/PostgreSQL).

---

## Test pyramid and levels

| Level | Scope | Environment | Purpose |
|-------|--------|-------------|---------|
| **Unit** | Single class/method, mocked dependencies | JUnit (backend), Jest (frontend) | Correctness of logic in isolation |
| **Integration** | Component + DB/API/event stream | Spring Boot Test, in-memory H2 | Contracts between layers |
| **System** | Full stack (API + optional UI) | Running backend + frontend | End-to-end flows via API/UI |
| **Acceptance** | User stories / business rules | Staging or local | “Done” from product perspective |
| **Performance** | Throughput, latency under load | Dedicated run | SLOs and bottlenecks |
| **Security** | Auth, injection, secrets, headers | All | Confidentiality, integrity, authZ |
| **Regression** | Existing behaviour after change | CI / pre-release | No unintended breakage |
| **Compatibility** | Browsers, OS, DB, Java/Node versions | Matrix | Works across supported combinations |
| **Chaos** | Faults (timeouts, failures, restarts) | Test env | Resilience and graceful degradation |

---

## 1. Unit test cases

### Backend (Java / JUnit 5)

| ID | Component | Test case | Input / scenario | Expected |
|----|-----------|-----------|-------------------|----------|
| U-B-1 | `BookingPolicyService` | `assertCanPay` allows RESERVED | Reservation status RESERVED, not expired | No exception |
| U-B-2 | `BookingPolicyService` | `assertCanPay` rejects EXPIRED | Reservation expired | `IllegalStateException` |
| U-B-3 | `BookingPolicyService` | `assertCanConfirm` allows PAID | Status PAID | No exception |
| U-B-4 | `RouteOrderConfig` | `ticketCoversSegment` | (tFrom, tTo, segOrigin, segDest) within route order | true/false per spec |
| U-B-5 | `TripSegmentService` | `idempotencyKey` format | passengerId, origin, dest, startTime | `passengerId\|origin\|dest\|epochSecond` |
| U-B-6 | `TripSegmentService` | `gpsConfidenceScore` | accuracy 10, 50, 100, 200 | 100, 80, 60, 40 |
| U-B-7 | `TripSegmentService` | `detectTicketSharing` – no overlap | Same reservationId, different passengers, non-overlapping times | 0 alerts |
| U-B-8 | `TripSegmentService` | `detectTicketSharing` – overlap | Same reservationId, 2 passengers, overlapping segment times | 1 audit, count 1 |
| U-B-9 | `FraudDetectionService` | Refund after travel | PENDING refund, reservationId has segment | 1 alert, FRAUD_REFUND_AFTER_TRAVEL |
| U-B-10 | `FraudDetectionService` | Refund abuse threshold | User with 6 refund requests in 30 days | 1 alert, FRAUD_REFUND_ABUSE |
| U-B-11 | `FraudDetectionService` | Multiple device | Same passengerId, 2 segments overlapping time, different routes | 1 alert, FRAUD_MULTIPLE_DEVICE |
| U-B-12 | `LocationService` | Point in circle | (lat, lon) inside geofence radius | Inside = true |
| U-B-13 | `PricingService` | Fallback when Drools fails | Trip, no buckets or rule error | Returns trip.getPricePerSeat() |
| U-B-14 | `GeofenceRulesService` | No ticket at entry (Java fallback) | hasTicket=false | Creates StationEntryAction + notification |

### Frontend (Jest / React Testing Library)

| ID | Component | Test case | Input / scenario | Expected |
|----|-----------|-----------|-------------------|----------|
| U-F-1 | `api.reportLocation` | Sends accuracy when present | (lat, lon, 15) | Body includes accuracyMeters: 15 |
| U-F-2 | `api.runFullSimulation` | URL without tripId | (secret) | POST to `/admin/simulation/run` no query |
| U-F-3 | `api.runFullSimulation` | URL with tripId | (secret, 5) | POST to `/admin/simulation/run?tripId=5` |
| U-F-4 | Date/time helpers | toISOAtLocalTime | "19:45" | ISO string for today 19:45 |
| U-F-5 | App (routing) | Renders landing when not logged in | No user in state | Landing / login visible |
| U-F-6 | App (auth) | After login, shows main view | User set | Bookings/trips or home visible |

---

## 2. Component test matrix (repositories, services, controllers)

Backend tests are organised by layer. Run with: `cd booking-service && mvn test`.

### 2.1 Repository layer (`RepositoryLayerTest`)

Each repository is tested with save/find and custom query methods (test profile, in-memory H2).

| Repository | Test cases |
|------------|------------|
| GeofenceRepository | findAllByOrderByNameAsc, findByStationName, findByName |
| TrainRepository | save, findById |
| SeatRepository | save, count |
| TripRepository | findAllWithTrain, findByIdWithTrain, findByFromStationAndToStation |
| TripSeatRepository | findByTripId, countByTripId, findByTripIdAndSeatIdForUpdate (@Transactional) |
| FareBucketRepository | findByTripIdOrderByDisplayOrderAsc |
| ReservationRepository | save, findByUserIdOrderByCreatedAtDesc, findActiveByTripSeatId, countByTripIdAndStatusIn |
| StationEntryActionRepository | save, findByUserIdAndStatus, existsByUserIdAndGeofenceIdAndStatus, countByStatus |
| GeofenceEventRepository | save, findByUserIdOrderByCreatedAtDesc, findAllByOrderByCreatedAtDesc |
| UserRepository | save, findByEmail, existsByEmail |
| UserLocationRepository | save, findByUserId, findAllByOrderByUpdatedAtDesc |
| AuditLogRepository | save, findByUserIdOrderByCreatedAtDesc, findAllByOrderByCreatedAtDesc |
| RefundRequestRepository | save, findByUserIdOrderByRequestedAtDesc, countByUserIdAndRequestedAtAfter |
| TripSegmentRepository | save, findByIdempotencyKey, findByPassengerId, findByFareStatusAndResolutionDeadlineBefore, countByFareStatus |
| TicketAlertRepository | save, findByUserIdOrderByCreatedAtDesc |
| UserNotificationRepository | save, findByUserIdOrderByCreatedAtDesc |
| PaymentSequenceRepository | save, findBySequenceDate, findBySequenceDateForUpdate (@Transactional) |

### 2.2 Service layer (unit tests)

| Service | Test class | Test cases |
|---------|------------|------------|
| BookingPolicyService | BookingPolicyServiceTest | getReservationTimeoutMinutes, reservationExpiresAt, validateSeatCount (valid/zero/over max), canProceedToPayment/Confirm, assertCanPay (pass/expired/wrong status), assertCanConfirm (pass/throw) |
| PricingService | PricingServiceTest | getPrice when no fare buckets (STANDARD fallback), when no seats (STANDARD), when fare buckets exist (dynamic tier) |
| LocationService | LocationServiceTest | reportLocation saves UserLocation, reportLocation when entering geofence calls recordEvent, getAllUserLocations |

### 2.3 Controller layer (`@WebMvcTest`)

| Controller | Test class | Test cases |
|-------------|------------|------------|
| BookingController | BookingControllerTest | GET /api/payment-methods, GET /api/trips (list with pricing), POST /api/reserve (with X-User-Id), POST /api/reserve without header (4xx) |
| WebhookController | WebhookControllerTest | POST /webhooks/stripe missing body (400), missing Stripe-Signature (401), with signature handled (200), verification failed (400) |

---

## 3. Integration test cases

### Backend (Spring Boot Test, @Transactional or test DB)

| ID | Scope | Test case | Steps | Expected |
|----|--------|-----------|--------|----------|
| I-B-1 | BookingService + Repos | Reserve → payment → confirm | reserve(user, tripId, [seatId]); payment(...); confirm(...) | Reservation CONFIRMED, seat locked |
| I-B-2 | BookingService | Double reserve same seat | Two reserve() same tripId+seatId (second after first confirm) | Second fails or seat already taken |
| I-B-3 | GeofenceService + Event | recordEvent publishes | recordEvent(userId, geofenceId, ENTERED, time, 10.0) | GeofenceEvent saved, pipeline receives ENTERED |
| I-B-4 | TripSegmentService | Segment creation from events | Save EXITED origin, then ENTERED dest (same user); trigger pipeline | One segment, origin→dest, fare status from ticket |
| I-B-5 | TripSegmentService | Train match | Segment start within ±5 min of trip.departureTime | matchedTripId set, confidence includes train score |
| I-B-6 | RefundService | Request refund within window | reservation PAID, requestRefund within request-window-minutes | RefundRequest PENDING |
| I-B-7 | RefundService | Request refund after window | reservation PAID, request after window | IllegalStateException |
| I-B-8 | FraudDetectionService | runAll() ticket sharing | Seed segments with same reservationId, 2 passengers, overlap | ticketSharingAlerts >= 1, audit FRAUD_TICKET_SHARING |
| I-B-9 | FraudDetectionService | runAll() consistency orphan | CONFIRMED reservation, trip in past, no segment | consistencyAlerts >= 1, FRAUD_CONSISTENCY_ORPHAN |
| I-B-10 | PaymentGatewayService (demo) | createPaymentIntent demo | reservationId, userId, STRIPE, demo-mode true | Reservation CONFIRMED, clientSecret returned |
| I-B-11 | LocationEventPipelineConsumer | ENTERED → onStationEntry + onStationEntryDetected | Publish GeofenceEventRecordedEvent ENTERED | GeofenceRulesService ran; segment created if EXITED prior |
| I-B-12 | SimulationService | runFullSimulation | Default (first trip with geofences) | createTicketForUser(sim-paid); 2 segments (PAID + PENDING_RESOLUTION) |

### Simulation with large dataset (170 journey scenarios)

| ID | Scope | Test case | Distribution | Metrics |
|----|--------|-----------|--------------|---------|
| I-B-13 | SimulatedJourneyDetectionTest | 170 simulated journeys: valid, no ticket, over travel, route violation, ticket sharing | Valid 50, No ticket 40, Over travel 40, Route violation 20, Ticket sharing 20 | **Correct rate:** valid journeys → PAID, no fraud alert (FPR). **True positive rate (TPR):** fraud scenarios correctly flagged (NO_TICKET, OVER_TRAVEL, WRONG_ROUTE/ROUTE_VIOLATION, TICKET_SHARING). Assertions: FPR ≤ 10%, TPR ≥ 85%. Run: `mvn test -Dtest=SimulatedJourneyDetectionTest`. |

Test data is seeded by `SimulatedJourneyTestDataInitializer` (test profile): geofences Leeds, Meadowhall Interchange, Sheffield, Rotherham Central; 101 trips (Leeds–Sheffield, Leeds–Meadowhall, Meadowhall–Sheffield, Sheffield–Rotherham). Report printed at end: valid correct count, fraud correct count, FPR, TPR.

### Frontend (optional: MSW or real API)

| ID | Scope | Test case | Steps | Expected |
|----|--------|-----------|--------|----------|
| I-F-1 | API client + backend | Login → get bookings | POST /auth/login then GET /bookings with header | 200, list of reservations |
| I-F-2 | API client | Admin tickets | GET /admin/tickets with X-Admin-Secret | 200, array of ticket DTOs |

---

## 4. System test cases

Full stack: backend running, frontend running (or API-only with curl/Postman).

| ID | Flow | Steps | Expected |
|----|------|--------|----------|
| S-1 | Register → Login | Register email/password; login same | 200, token/user; GET /bookings works with X-User-Id |
| S-2 | Book trip E2E | Login → get trips → reserve → payment (demo) → confirm | Reservation CONFIRMED; appears in My bookings |
| S-3 | Geofence → no-ticket action | Admin record ENTERED for user without ticket; GET station-entry-actions/pending as user | Pending action; modal in app (Buy / Ignore / Scan QR) |
| S-4 | Validate QR | Create CONFIRMED booking; trigger no-ticket entry; POST validate-qr with reservationId | Action completed; no modal |
| S-5 | Location → segment | POST /location at origin; then at destination (or simulate journey); GET admin trip-segments | Segment created, origin→destination, fare status correct |
| S-6 | Run full simulation | POST /admin/simulation/run with admin secret | 200; segments for sim-paid (PAID) and sim-unpaid (PENDING_RESOLUTION) |
| S-7 | Fraud detection run | POST /admin/fraud-detection/run | 200; JSON with ticketSharingAlerts, refundFraudAlerts, ... |
| S-8 | Refund request → approve | User request refund (within window); admin approve | RefundRequest APPROVED; reservation REFUNDED |
| S-9 | Stripe webhook (if configured) | Stripe CLI forward payment_intent.succeeded | Reservation PAID then CONFIRMED (idempotent on duplicate) |

---

## 5. Acceptance test cases

User-story style; can be automated (e.g. Playwright, Cypress) or manual.

| ID | Story / rule | Scenario | Acceptance criteria |
|----|--------------|----------|---------------------|
| A-1 | User can book a train ticket | User selects trip, seat, pays (demo) | Ticket appears in My bookings; status CONFIRMED; can download PDF |
| A-2 | User without ticket is prompted at station | User enters station geofence, no CONFIRMED ticket from that station | Modal: Buy ticket / Ignore / I have a ticket (scan QR) |
| A-3 | User with ticket can validate at station | User has CONFIRMED ticket; enters station; chooses “I have a ticket” and enters reservation ID | Action resolved; no penalty |
| A-4 | Fare evasion resolution window | User travels without ticket → segment PENDING_RESOLUTION | After resolution window, segment can become UNPAID_TRAVEL; penalty applied if confidence ≥ threshold |
| A-5 | Admin sees live user locations | Admin opens Geofence monitor; user reports location | User appears in “Live user locations” with last position |
| A-6 | Admin can run fraud detection | Admin clicks “Run fraud detection” | Counts per type returned; audit log contains FRAUD_* entries for any findings |
| A-7 | User can request refund within window | User paid within last N minutes; requests refund | Refund request PENDING; admin can approve/reject |
| A-8 | Journey reconstruction shows confidence | Segment created from geofence events with good accuracy and matching train | Segment has confidenceScore and matchedTripId; PAID if ticket covers route |

---

## 6. Performance test cases

| ID | Scenario | Load / metric | Tool / method | Target |
|----|----------|----------------|---------------|--------|
| P-1 | Concurrent geofence entries | 300 users ENTERED same geofence | POST /admin/load-test/geofence-entries?geofenceId=1&concurrentUsers=300 | successCount=300, no deadlocks |
| P-2 | Concurrent QR validations | N parallel validate-qr (same action/reservation handled once) | POST /admin/load-test/validate-qr | successCount ≤ N, no double-use |
| P-3 | Reserve under contention | Many users reserve same trip (different seats) | scripts/test-concurrent-booking.js or similar | No double booking; response time p95 &lt; 2s |
| P-4 | List trips | GET /trips 100 times | Artillery / k6 / ab | p95 &lt; 500ms |
| P-5 | Fraud detection run | POST /admin/fraud-detection/run with large data | Single run | Completes in &lt; 30s |
| P-6 | Journey reconstruction throughput | Many ENTERED events in short time | Simulate many users entering different geofences | Segments created; no backlog buildup (if async, drain within N s) |

---

## 7. Security test cases

| ID | Area | Test case | Steps | Expected |
|----|------|-----------|--------|----------|
| SEC-1 | Admin auth | Admin endpoint without X-Admin-Secret | GET /api/admin/geofences no header | 401/403 |
| SEC-2 | Admin auth | Admin endpoint with wrong secret | GET /api/admin/geofences X-Admin-Secret: wrong | 401/403 |
| SEC-3 | User isolation | User A cannot see user B bookings | GET /bookings with X-User-Id: B as A | 403 or only A’s data |
| SEC-4 | User isolation | User cannot confirm another user’s reservation | POST confirm with reservationId of B, X-User-Id: A | 403/404 |
| SEC-5 | Payment | Payment intent only for own reservation | createPaymentIntent(reservationId of B, userId A) | 403/404 |
| SEC-6 | Webhook | Stripe webhook with invalid signature | POST /webhooks/stripe with bad signature | 400/401, no state change |
| SEC-7 | Injection | Trip search / audit log filter | userId or params with SQL-like string | No SQL injection; parameterized queries |
| SEC-8 | Secrets | Admin secret not in client bundle | Build frontend; grep for admin secret | Not in static assets |
| SEC-9 | CORS | Request from disallowed origin | GET /api/trips from https://evil.com | CORS blocks or 403 |

---

## 8. Regression test cases

Run after any change to ensure existing behaviour holds. Subset of unit + integration + system that is fast and stable.

| ID | Area | Test case | Trigger |
|----|------|-----------|---------|
| R-1 | Booking | Reserve → pay (demo) → confirm | BookingService + controller |
| R-2 | Geofence | Record ENTERED → event stored and pipeline runs | GeofenceService + LocationEventPipelineConsumer |
| R-3 | Segment | EXIT origin + ENTER dest → one segment, fare from ticket | TripSegmentService |
| R-4 | Ticket sharing | Two segments same reservationId different passengers overlap → 1 alert | TripSegmentService.detectTicketSharing |
| R-5 | Refund | Request within window → PENDING; after window → reject | RefundService |
| R-6 | Admin | GET admin/trip-segments, GET admin/geofences with valid secret | BookingController |
| R-7 | Fraud run | POST admin/fraud-detection/run returns 200 and shape | FraudDetectionService |
| R-8 | Simulation | POST admin/simulation/run returns segments for sim-paid and sim-unpaid | SimulationService |

---

## 9. Compatibility test cases

| ID | Dimension | Variants | Test focus |
|----|------------|----------|------------|
| C-1 | Browser | Chrome, Firefox, Safari, Edge (latest) | Login, book trip, geofence modal, admin dashboard |
| C-2 | Mobile | iOS Safari, Android Chrome | Same flows; location permission; responsive layout |
| C-3 | Java version | 17 (LTS) | Backend unit + integration tests; mvn test |
| C-4 | Node version | 18, 20 LTS | npm install, npm run build, npm test |
| C-5 | Database | H2 (file), PostgreSQL | Spring profiles; reserve/payment/confirm; geofence events |
| C-6 | API base URL | Relative path, absolute URL (same origin, cross-origin) | Frontend apiBase(); CORS when cross-origin |

---

## 10. Chaos test cases

Introduce failures and verify resilience.

| ID | Fault | Injection | Expected system behaviour |
|----|-------|-----------|---------------------------|
| CH-1 | Stripe webhook timeout | Slow or no response from Stripe (mock) | Webhook handler times out; no duplicate CONFIRMED; retry safe (idempotent) |
| CH-2 | DB connection lost | Restart DB or kill connections during request | Requests fail gracefully; no partial commits (transactional boundaries); recovery after DB back |
| CH-3 | Kafka unavailable (if used) | Disable Kafka | Location events fall back to in-process; geofence events still processed |
| CH-4 | High latency on /location | Delay POST /location by 5s | App does not block indefinitely; backend eventually processes or rejects |
| CH-5 | Concurrent reserve same seat | Two requests reserve same seat at same time | One succeeds, one fails (pessimistic lock); no double booking |
| CH-6 | Drools failure | Corrupt or remove rule file | GeofenceRulesService falls back to Java (no ticket → create action); PricingService falls back to trip price |
| CH-7 | Admin secret rotated | Change booking.admin.secret mid-session | Old secret rejected; new secret required for admin APIs |
| CH-8 | Frontend API base wrong | REACT_APP_API_URL points to down host | User sees “Booking service not reachable” or similar; no silent failure |

---

## Implementation notes

- **Backend unit/integration:** Add tests in `booking-service/src/test/java` (JUnit 5, Mockito, `@SpringBootTest`, `@DataJpaTest`, or `@WebMvcTest` as needed). Use `@ActiveProfiles("test")` and in-memory H2.
- **Frontend unit:** Jest + React Testing Library; extend `src/App.test.js` and add tests for components and API client (mock fetch).
- **System / acceptance:** Use [TESTING.md](../TESTING.md) for manual flows; automate with Playwright/Cypress for critical user journeys.
- **Performance:** Use existing `POST /admin/load-test/geofence-entries` and `validate-qr`; add k6/Artillery for API load.
- **Security:** Manual or automated (e.g. OWASP ZAP, custom scripts for SEC-1–SEC-9).
- **Regression:** Run unit + selected integration tests in CI on every commit; full regression suite before release.
- **Compatibility:** Browser matrix in CI (e.g. Sauce Labs) or periodic manual; Java/Node/DB in CI matrix.
- **Chaos:** Optional chaos engineering (e.g. Chaos Monkey, custom test that kills DB or delays gateway); start with CH-5 (concurrent reserve) as integration test.

---

## Quick reference – test commands

```bash
# Backend unit/integration (when tests exist)
cd booking-service && mvn test

# Frontend unit
npm test

# System (manual): start backend + frontend, then run flows from TESTING.md
# Performance: load-test endpoints (see TESTING.md §4)
# Fraud run: curl -X POST http://localhost:8080/api/admin/fraud-detection/run -H "X-Admin-Secret: admin123"
# Simulation: curl -X POST http://localhost:8080/api/admin/simulation/run -H "X-Admin-Secret: admin123"
```

This document should be updated when new features (e.g. new fraud checks, new APIs) are added; add new test case rows to the appropriate level and reference them in regression where relevant.

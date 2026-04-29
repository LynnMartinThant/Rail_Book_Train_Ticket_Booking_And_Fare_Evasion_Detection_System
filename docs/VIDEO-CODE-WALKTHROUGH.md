# RailBook — Video script + code walkthrough

Use this document while recording: each section matches your narration timing and points to **exact files and what to say** when those files are on screen.

---

## 0:00 – 1:00 · Problem & product framing

**Script (summary):** RailBook is behaviour-aware railway validation. Traditional systems validate at purchase only; they do not continuously check whether travel behaviour matches entitlement. RailBook makes validation **continuous, event-driven**: movement → reconstruction → policy → confidence.

**Optional code (5–10s):** Show that the system is built on **named events**, not only database rows.

- **File:** `booking-service/src/main/java/com/train/booking/movement/eventlog/MovementEventType.java`  
- **Say:** “Every important step is a named event we can log, trace, and audit.”

---

## 1:00 – 2:30 · Architecture (modular monolith + hierarchical layers)

**Script (summary):** Single backend deployable (simple to build and demo). Internally: hierarchical, **event-driven** layers — ingestion, data quality, station processing, journey coordination, fare policy, fraud policy, admin supervision. Events link stages; same design can later use Kafka without redesigning core logic.

### File A — Layer ownership

- **File:** `booking-service/src/main/java/com/train/booking/platform/MovementSourceLayer.java`  
- **Walkthrough:** Each enum value is the **owning layer** for events. Phase 1 = one JVM; tags stay on every event for future split.

### File B — Persist then publish (internal event bus)

- **File:** `booking-service/src/main/java/com/train/booking/movement/eventlog/MovementEventWriter.java`  
- **Focus:** Method `append(...)` — save `MovementEventEntity`, then `movementEventStream.publish(MovementEventEnvelope...)`.  
- **Say:** “Events are durable first, then published — same contract we’d use with an external broker later.”

### File C — Event vocabulary (optional second tab)

- **File:** `booking-service/src/main/java/com/train/booking/movement/eventlog/MovementEventType.java`  
- **Say:** “These are the pipeline stages in code: location, quality, geofence, journey confirmed, fare validated, fraud, disputes.”

---

## 2:30 – 4:30 · Data flow (core technical section)

**Script (summary):** `LocationReported` with metadata and **correlation ID** → `DataQualityAssessed` (inference vs enforcement) → low quality retained but not used for enforcement → geofence enter/exit → journey segments → fare policy vs entitlement → fraud policy. **Correlation IDs** tie the full chain.

### Step 1 — Ingestion + correlation

- **File:** `booking-service/src/main/java/com/train/booking/service/LocationService.java`  
- **Focus:** `reportLocation` — `UUID correlationId`, `LocationReported` payload, `MOVEMENT_INGESTION`.

### Step 2 — Data quality event

- **Same file:** `DataQualityScoringService.assess(...)`, then `DataQualityAssessed` append with `trustScore`, `usableForInference`, `usableForEnforcement`, `issues`.

### Step 3 — Gate: inference vs reject

- **Same file:** `if (quality.usableForInference())` → `geofenceService.applyLocationReportForStationTransitions(...)`; else `LocationRejected` with reason/trust/issues.

### Step 4 — Station layer

- **File:** `booking-service/src/main/java/com/train/booking/service/GeofenceService.java`  
- **Focus:** `recordEvent` — builds payload, maps ENTERED/EXITED to `GeofenceEntered` / `GeofenceExited`, `movementEventWriter.append(..., STATION_PROCESSING)` with same `correlationId` when passed in.

### Step 5 — Fare policy: confirmed segment only, not raw GPS

- **File:** `booking-service/src/main/java/com/train/booking/platform/farepolicy/FarePolicyConsumer.java`  
- **Focus:** Class javadoc + `onMovementEvent` — returns early unless `JourneySegmentConfirmed`.  
- **Say:** “Fare policy never reads GPS; it reacts only to a confirmed journey fact.”

**Fraud policy** uses the same listener pattern (consume movement events, emit decisions). Mention in voiceover if you do not open the file.

---

## 4:30 – 5:30 · How you built it

**Script (summary):** Spring Boot modular monolith; packages per layer; Spring Data JPA; in-process events (future Kafka); React passenger + admin; unit, integration, simulation tests.

**Quick tour:**

| What | Where |
|------|--------|
| Build | `booking-service/pom.xml` |
| Backend packages | `booking-service/src/main/java/com/train/booking/` (`movement`, `platform`, `confidence`, `decision`, `service`, `domain`, …) |
| Frontend | `src/App.js`, `src/components/AdminDashboard.js`, `src/api/client.js` |
| Tests | `booking-service/src/test/java/com/train/booking/` |

**Say:** “Behaviour is validated with simulations and integration tests, not only isolated unit tests.”

---

## 5:30 – 7:00 · Live: booking → movement

**Script (summary):** Booking/ticketing is **authoritative entitlement**. Movement is **behaviour evidence**. Quality can filter or downgrade; valid data becomes geofence transitions.

### Booking authority

- **File:** `booking-service/src/main/java/com/train/booking/api/BookingController.java`  
- **Search:** `reserve`, `confirm`, `bookings` — one screen, don’t read every line.  
- **Say:** “Tickets and journey entitlement are defined here; the movement pipeline does not replace that source of truth.”

### Movement HTTP entry

- **Same file:** `@PostMapping("/location")` → delegates to `LocationService.reportLocation` (already covered above).

**Live demo:** App reports location → Admin “Recent movement events” shows `LocationReported`, `DataQualityAssessed`, then geofence events when quality allows.

---

## 7:00 – 8:30 · Confidence + decision engine

**Script (summary):** Confidence is a **computed contract** (dimensions + penalties + band). A **deterministic decision engine** uses coverage, route, fraud signal, confidence, data quality, unresolved flags. **Confidence controls behaviour** (auto vs review vs unresolved), not just display.

### Confidence scoring

- **File:** `booking-service/src/main/java/com/train/booking/confidence/DefaultConfidenceScoringService.java`  
- **Focus:** `assess` — sub-scores (geofence, temporal, movement completeness, route, entitlement), `penaltyScore`, weighted `total`, `ConfidenceBand` HIGH/MEDIUM/LOW, `ConfidenceBreakdown` with `reasons`.

### Decision table

- **File:** `booking-service/src/main/java/com/train/booking/decision/DeterministicSegmentDecisionService.java`  
- **Focus:** `enforcementSafe` (STRONG quality + HIGH band); branches to `PENDING_RESOLUTION`, `PENDING_REVIEW`, `PAID`, `UNDERPAID`, `UNPAID_TRAVEL` (with `enforcementSafe`), `ESCALATED_FRAUD_REVIEW`, default review.  
- **Say:** “Same evidence pattern → same outcome; punitive automation is gated.”

**Related:** Outcome application and segment persistence live in `TripSegmentService` (search `segmentDecisionService` / `ConfidenceScoringService` if you need one extra file).

---

## 8:30 – 9:30 · Lifecycle state machine

**Script (summary):** Formal states from detection through fare assessment to outcomes; fairness states `DISPUTED`, `RECOMPUTING`, `OVERTURNED_TO_PAID`, `CLOSED`. **Transitions** stored with trigger, reason, correlation ID.

### State enum

- **File:** `booking-service/src/main/java/com/train/booking/domain/SegmentState.java`

### Persisted transitions

- **File:** `booking-service/src/main/java/com/train/booking/service/SegmentStateMachineService.java`  
- **Focus:** `transition` — updates `TripSegment.segmentState`, appends `SegmentTransition` (from, to, trigger, reason, correlationId).

**Say:** “Current state plus history — you can replay how we got here.”

---

## 9:30 – 10:30 · Dispute & recomputation

**Script (summary):** Dispute is **first-class**: dispute record, recomputation, **append-only** lineage; original decision preserved; final state `OVERTURNED_TO_PAID` or `CLOSED`.

### API

- **File:** `booking-service/src/main/java/com/train/booking/api/BookingController.java`  
- **Focus:**  
  - `POST /my/trip-segments/{segmentId}/upload-ticket` → `disputeService.submitDisputeAndRecompute`  
  - `GET /my/trip-segments/{segmentId}/disputes`, `GET .../recomputations`  
  - `GET /admin/disputes`

### Orchestration

- **File:** `booking-service/src/main/java/com/train/booking/service/DisputeService.java`  
- **Focus:** `submitDisputeAndRecompute` — capture `previousDecision`, create `DisputeRecord`, `DISPUTED` + `DisputeSubmitted` event, `RECOMPUTING`, `RecomputationRecord`, `OVERTURNED_TO_PAID` vs `CLOSED`, dispute status ACCEPTED/REJECTED.

### Domain / persistence

- **Files:** `domain/DisputeRecord.java`, `domain/RecomputationRecord.java`, `repository/DisputeRecordRepository.java`, `repository/RecomputationRecordRepository.java`

---

## 10:30 – 11:15 · Admin evidence & explainability

**Script (summary):** Operators inspect movement, decisions, confidence, transitions by **correlation ID**; segments expose state, band, reasons, transition history, disputes, recomputations.

### Evidence endpoint

- **File:** `booking-service/src/main/java/com/train/booking/api/BookingController.java`  
- **Focus:** `GET /admin/evidence` — load by `correlationId`, build `timeline`, `narrative`, `segmentTransitions`.

### Rich segment DTO

- **Same file:** `toTripSegmentDto` — `segmentState`, `transitions`, `disputes`, `recomputations`, `confidenceBand`, `confidenceReasons` from `explanationJson`.

### Narrative builder

- **Same file:** inner class `EvidenceNarrativeBuilder` (optional — shows how timeline becomes human-readable lines).

### Admin UI

- **File:** `src/components/AdminDashboard.js`  
- **Search:** `Evidence view`, `Dispute queue`, `getAdminEvidence`, `getAdminDisputes`, trip segment table columns.

---

## 11:15 – 12:00 · Closing

**Script (summary):** RailBook turns validation from a one-time check into **continuous behavioural evaluation** under uncertainty, with **transparency** and **fair** dispute handling.

**Optional 15s recap montage (file order):**  
`MovementEventType` → `LocationService` (quality gate) → `GeofenceService` → `FarePolicyConsumer` → `DefaultConfidenceScoringService` → `DeterministicSegmentDecisionService` → `BookingController` (`getEvidence`).

---

## Filming checklist (printable)

| # | File | Highlight |
|---|------|-----------|
| 1 | `MovementSourceLayer.java` + `MovementEventType.java` | Layers + event names |
| 2 | `LocationService.java` | Correlation, quality, reject |
| 3 | `GeofenceService.java` | Station events |
| 4 | `MovementEventWriter.java` | Persist + publish |
| 5 | `FarePolicyConsumer.java` | Only `JourneySegmentConfirmed` |
| 6 | `DefaultConfidenceScoringService.java` | Formula + band |
| 7 | `DeterministicSegmentDecisionService.java` | Decision table |
| 8 | `SegmentState.java` + `SegmentStateMachineService.java` | States + transitions |
| 9 | `DisputeService.java` + dispute routes in `BookingController.java` | Fairness flow |
| 10 | `BookingController.java` (`getEvidence`, `toTripSegmentDto`) | Audit API |
| 11 | `AdminDashboard.js` | Visible admin proof |

---

## Tips

- Zoom editor font (140–160%), one main idea per file, ~45–75 seconds per file for a 10–12 minute technical section.
- Bridge line: *“If I open the code behind that behaviour, this is what implements it.”*
- Split screen: IDE left, running app right, when demoing live movement and admin panels.

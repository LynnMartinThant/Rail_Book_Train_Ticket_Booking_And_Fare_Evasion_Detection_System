# Geofence Admin System – Implementation & Use-Case Scenarios

This document describes how the admin geofence system is implemented and how each feature supports real-world scenarios.

---

## Feature checklist

| Feature | Status | Implementation |
|--------|--------|-----------------|
| Accurate polygon geofence boundaries | ✔ | Optional polygon per geofence; point-in-polygon when set |
| Platform-level geofence segmentation | ✔ | `platform` field per geofence; used in trip segments |
| GPS noise filtering enabled | ✔ | `booking.geofence.gps-min-accuracy-meters`; reject bad fixes for events |
| Event debouncing configured | ✔ | `booking.geofence.debounce-enter-ms`; suppress duplicate ENTERED |
| Train schedule synchronization active | ✔ | Trip segments use `TripRepository` / route order for fares and coverage |
| Ticket validation rules configured | ✔ | Drools + `RouteOrderConfig`; PAID / PENDING_RESOLUTION / UNPAID |
| False positive monitoring dashboard | ✔ | Admin “Fare evasion cases” + user dispute (upload ticket) |
| Monthly geofence calibration | ✔ | `POST /api/admin/geofence-calibration`; audit log; config flag |
| Edge case handling | ✔ | Idempotency, same-station skip, resolution window, penalty scheduler |
| System health monitoring | ✔ | `GET /api/admin/geofence-health`; dashboard card + Run calibration |

---

## 1. Accurate polygon geofence boundaries

**Implementation**

- **Domain:** `Geofence` has an optional `polygonGeoJson` (TEXT) storing a JSON array of `[lat, lon]` points forming a closed ring, e.g.  
  `[[53.38,-1.46],[53.39,-1.46],[53.39,-1.45],[53.38,-1.45]]`.
- **LocationService:** For each geofence, `isInside(geofence, userLat, userLon)`:
  - If `polygonGeoJson` is present and valid: use **point-in-polygon** (ray-casting).
  - Otherwise: use **circle** (centre + `radiusMeters`).
- **API:** `GeofenceDto` and `GET /api/admin/geofences` include `polygonGeoJson` when set.
- **Config:** No extra config; polygon is optional. Existing geofences keep circle behaviour.

**Use-case scenario**

- **Problem:** A station has a curved platform; a circle either cuts into the street (false entries) or misses part of the platform (missed entries).
- **Scenario:** Admin defines a polygon that follows the platform edge. Only users whose GPS falls inside the polygon trigger ENTERED/EXITED. This reduces false triggers from the car park and improves detection on the platform.

---

## 2. Platform-level geofence segmentation

**Implementation**

- **Domain:** Each `Geofence` has an optional `platform` (e.g. `1A`, `3B`).
- **Seeding:** `GeofenceDataInitializer` creates per-station geofences with platform (e.g. Sheffield 1A, 2A, etc.).
- **Trip segments:** `TripSegmentService.onStationEntryDetected` reads `destinationGeofence.getPlatform()` and `originGeofence.getPlatform()` and stores them on `TripSegment` (`originPlatform`, `destinationPlatform`).
- **Admin / API:** Geofence list and trip-segment list show platform; admin can filter or analyse by platform.

**Use-case scenario**

- **Problem:** Operations need to know which platform a passenger used (e.g. for crowding or delay analysis).
- **Scenario:** User exits “Sheffield Platform 2A” and enters “Doncaster Platform 1B”. The created trip segment stores both platforms. Admin sees “2A → 1B” in the False positive monitoring table and can correlate with train schedules and ticket validity.

---

## 3. GPS noise filtering enabled

**Implementation**

- **Config:** `booking.geofence.gps-min-accuracy-meters` (default `50`). Set to `0` to disable.
- **LocationService:** `reportLocation(userId, lat, lon, accuracyMeters)`:
  - If `accuracyMeters != null` and `accuracyMeters > gpsMinAccuracyMeters`: location is **saved** (map position updates) but **no ENTERED/EXITED events** are emitted for this update.
  - So poor fixes (e.g. 200 m accuracy) do not trigger spurious geofence events.
- **Client:** If the app sends an accuracy value (e.g. from `Geolocation.getCurrentPosition()` or similar), the backend uses it; otherwise events are still driven by position only.

**Use-case scenario**

- **Problem:** Indoors or in urban canyons, GPS sometimes jumps (e.g. 150 m error). That can cause “enter station → exit station → enter again” in a few seconds.
- **Scenario:** App sends `accuracy: 120` (metres). Backend has `gps-min-accuracy-meters: 50`. This update is not used for geofence logic; the next update with better accuracy is. Fewer false “double entry” or “exit then re-enter” events.

---

## 4. Event debouncing configured

**Implementation**

- **Config:** `booking.geofence.debounce-enter-ms` (default `15000` = 15 seconds).
- **LocationService:** Before recording an **ENTERED** for `(userId, geofenceId)`:
  - Query last ENTERED for that user and geofence (`GeofenceEventRepository.findTop1ByUserIdAndGeofenceIdAndEventTypeOrderByCreatedAtDesc`).
  - If that event exists and `lastCreatedAt + debounceEnterMs > now`, **do not** record another ENTERED.
- **EXITED** is not debounced (each exit is recorded). Set `debounce-enter-ms: 0` to disable.

**Use-case scenario**

- **Problem:** GPS jitter at the boundary can cause several ENTERED events within seconds for the same station.
- **Scenario:** User walks into Sheffield station; first ENTERED is recorded and the pipeline runs (Drools, trip segment, etc.). Further ENTERED events for Sheffield within 15 seconds are ignored. After 15 seconds, a new ENTERED (e.g. after a brief exit and re-enter) is allowed. This reduces duplicate “no ticket at entry” notifications and duplicate segment creation.

---

## 5. Train schedule synchronization active

**Implementation**

- **Trip segments:** When a segment is created (origin EXIT → destination ENTER), the **route fare** is taken from `TripRepository.findByFromStationAndToStation(origin, destination)` (first trip’s `pricePerSeat`). So fares are aligned with **current trip/schedule data**.
- **Departures:** `GET /api/stations/{stationName}/departures` returns future departures from that station for display (e.g. admin or app by platform).
- **Route order:** `booking.route.station-order` (Hallam Line order) is used to decide if a ticket “covers” a segment (see Ticket validation). Schedule data (trips) and route order together give “train schedule synchronization” for validation and reporting.

**Use-case scenario**

- **Problem:** Fare evasion logic must use the same routes and fares as the timetable.
- **Scenario:** A segment Sheffield → Leeds is created. The system looks up a Sheffield–Leeds trip, gets `pricePerSeat`, and uses it as the route fare. Ticket coverage is checked against the configured station order. Admin and reports stay consistent with the live schedule.

---

## 6. Ticket validation rules configured

**Implementation**

- **Drools:** `GeofenceRulesService.onStationEntry` runs rules in `geofence.drl`. If “no ticket at entry” is detected, it creates `StationEntryAction` (PENDING_OPTION) and sends a user notification (Buy / Ignore / Scan QR).
- **Segment validation:** `TripSegmentService.buildSegment`:
  - Loads user’s CONFIRMED/PAID reservations.
  - Uses `RouteOrderConfig.ticketCoversSegment(tFrom, tTo, originStation, destinationStation)` (route order from `application.yml`).
  - **PAID:** A reservation covers the segment → segment marked PAID.
  - **PENDING_RESOLUTION:** No covering ticket → segment created with resolution deadline (e.g. 1 hour); penalty applied after window if not resolved.
  - **UNPAID_TRAVEL / UNDERPAID:** After resolution window or partial coverage logic.
- **Dispute:** User can call `POST /api/my/trip-segments/{id}/upload-ticket` with a reservation ID to dispute (false positive).

**Use-case scenario**

- **Problem:** Need consistent rules for “has valid ticket for this journey” and what happens when they don’t.
- **Scenario:** User travels Leeds → Sheffield with a Leeds–Sheffield ticket. Segment is created; ticket is checked against route order; segment marked PAID. Another user travels the same segment with no ticket; segment is PENDING_RESOLUTION; they get a notification; after the window, penalty is applied. A third user is wrongly flagged (e.g. ticket not yet linked); they upload ticket via the app; segment is updated and treated as resolved (false positive handled).

---

## 7. False positive monitoring dashboard

**Implementation**

- **Admin UI:** In the Geofence monitor tab, the **“False positive monitoring dashboard”** section lists **Fare evasion cases** (trip segments with PENDING_RESOLUTION or UNPAID_TRAVEL). Columns include passenger, origin, destination, platforms, fare status.
- **API:** `GET /api/admin/fare-evasion-cases` returns these segments. Users resolve false positives via `POST /api/my/trip-segments/{id}/upload-ticket` (dispute with ticket).
- **Audit:** Fare evasion actions are logged; admin can also use “Fare evasion” audit log list to see history.

**Use-case scenario**

- **Problem:** Some “no ticket” segments are wrong (e.g. user had a valid ticket but it wasn’t matched, or they were at a different platform).
- **Scenario:** Admin opens Geofence monitor and sees the False positive monitoring table. They see a segment for user X, Sheffield → Leeds, PENDING_RESOLUTION. User X uploads a ticket in the app; the segment is updated. Admin refreshes and sees the case resolved. They use the same table to confirm penalties for real evasion and to spot patterns (e.g. one station often mis-detected).

---

## 8. Monthly geofence calibration

**Implementation**

- **Config:** `booking.geofence.calibration-enabled` (default `true`). When `true`, calibration can be run.
- **API:** `POST /api/admin/geofence-calibration` (admin secret required). Records a run in the audit log (`GEOFENCE_CALIBRATION`) and returns the same payload as health (updated last calibration time).
- **Health:** `GET /api/admin/geofence-health` returns `lastCalibration` (timestamp of most recent calibration log entry) and `calibrationEnabled`.
- **Admin UI:** “System health monitoring” card shows “Calibration: &lt;date&gt;” and a **“Run calibration”** button when calibration is enabled. Button calls the calibration API and refreshes health.

**Use-case scenario**

- **Problem:** Over time, hardware or environment changes (e.g. new buildings, antenna changes) may require geofence boundaries or radii to be reviewed.
- **Scenario:** Once a month, an operator opens Admin → Geofence monitor, checks “System health” and runs “Run calibration”. This records that a calibration pass was done. In a future enhancement, calibration could suggest radius or polygon adjustments from recent event data; for now it provides a recorded checkpoint and keeps “last calibration” visible for audits and ops.

---

## 9. Edge case handling

**Implementation**

- **Idempotency:** Trip segments are keyed by `userId|originStation|destinationStation|segmentStartTime` (epoch second). Duplicate events for the same journey do not create duplicate segments.
- **Same station:** If origin and destination station names are equal, no segment is created (avoids “Sheffield → Sheffield” from jitter).
- **Segment order:** Only the most recent EXITED events are considered for pairing with the current ENTERED; segment start must be before entry time.
- **Resolution window:** PENDING_RESOLUTION segments get a deadline; a scheduled job (e.g. every 5 minutes) checks overdue segments and applies penalty (configurable amount and interval).
- **Debouncing and GPS filter:** As above; reduce duplicate and bad-fix-driven events.

**Use-case scenario**

- **Problem:** Duplicate events, same-station re-entry, or late-arriving events could create wrong or duplicate segments and penalties.
- **Scenario:** User exits Sheffield, then enters Sheffield again (e.g. walked out and back). No segment is created. User exits Sheffield, enters Leeds; one segment is created. If the same EXIT/ENTER is replayed (e.g. Kafka replay), the idempotency key prevents a second segment. Resolution window ensures that only after the configured time without a ticket upload is the penalty applied, avoiding penalizing users who are still in the process of uploading.

---

## 10. System health monitoring

**Implementation**

- **API:** `GET /api/admin/geofence-health` returns:
  - `status`: `"ok"`
  - `geofenceCount`: number of geofences
  - `eventsLast24h`: count of geofence events in the last 24 hours
  - `lastCalibration`: ISO timestamp of last calibration run, or `null`
  - `calibrationEnabled`: value of `booking.geofence.calibration-enabled`
- **Admin UI:** In the Geofence monitor tab, the **“System health monitoring”** card shows status, geofence count, events (24h), last calibration time, and (when enabled) the “Run calibration” button.
- **Client:** `getAdminGeofenceHealth(secret)` and `runAdminGeofenceCalibration(secret)` in `src/api/client.js`.

**Use-case scenario**

- **Problem:** Ops need to see at a glance that the geofence pipeline is active and when it was last calibrated.
- **Scenario:** Operator opens Admin → Geofence monitor. The health card shows “ok”, “12 geofence(s)”, “47 events (24h)”, “Calibration: 01/03/2026, 10:00”. They can run calibration and see the timestamp update. If events (24h) were 0 for a long time, they might investigate app or backend connectivity.

---

## Configuration summary

In `booking-service/src/main/resources/application.yml`:

```yaml
booking:
  geofence:
    gps-min-accuracy-meters: 50   # 0 = disable GPS filter
    debounce-enter-ms: 15000      # 0 = disable debounce
    calibration-enabled: true     # allow POST /api/admin/geofence-calibration
```

---

## API summary (admin)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/geofences` | List geofences (incl. optional `polygonGeoJson`) |
| POST | `/api/admin/geofences` | Create geofence (circle; polygon can be added via DB/API extension) |
| GET | `/api/admin/geofence-events` | Recent geofence events |
| GET | `/api/admin/geofence-health` | System health (status, counts, last calibration) |
| POST | `/api/admin/geofence-calibration` | Run calibration (when enabled) |
| GET | `/api/admin/fare-evasion-cases` | Trip segments for false positive monitoring |
| GET | `/api/admin/fare-evasion` | Fare evasion audit log |

All require header: `X-Admin-Secret: <secret>`.

---

## Where to find the code

| Feature | Main files |
|--------|------------|
| Polygon + circle containment | `LocationService.java` (`isInside`, `pointInPolygon`), `Geofence.java` (`polygonGeoJson`) |
| Platform segmentation | `Geofence.java` (`platform`), `TripSegmentService.java`, `TripSegment` (origin/destination platform) |
| GPS noise filter | `LocationService.reportLocation(..., accuracyMeters)`, `application.yml` (`gps-min-accuracy-meters`) |
| Debouncing | `LocationService.shouldDebounceEnter`, `GeofenceEventRepository.findTop1...`, `application.yml` (`debounce-enter-ms`) |
| Train schedule / fares | `TripSegmentService.getFareForRoute`, `TripRepository`, `GET /api/stations/{name}/departures` |
| Ticket validation | `TripSegmentService.buildSegment`, `RouteOrderConfig`, `GeofenceRulesService`, `geofence.drl` |
| False positive dashboard | `AdminDashboard.js` (False positive monitoring), `GET /api/admin/fare-evasion-cases`, upload-ticket endpoint |
| Calibration | `GeofenceService.runCalibration`, `getGeofenceHealth`, `POST/GET` geofence-calibration/health, `AuditLogService` |
| Edge cases | `TripSegmentService` (idempotency key, same-station skip, resolution deadline), penalty scheduler |
| Health | `GeofenceService.getGeofenceHealth`, `GET /api/admin/geofence-health`, Admin dashboard “System health monitoring” card |

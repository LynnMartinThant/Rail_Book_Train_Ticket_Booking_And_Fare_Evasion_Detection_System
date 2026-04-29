# Testing Use Cases in Real Life

This guide explains how to test the three main use cases **individually**—with a real device and/or the admin simulation tools.

---

## Prerequisites

- **Backend:** `cd booking-service && mvn spring-boot:run`
- **Frontend:** `npm start` (and optionally open the app on your phone, or use the same browser for passenger + admin)
- **Admin:** Log in at `/admin` with your admin secret (see `application.yml`: `booking.admin.secret`)

Stations with geofences are seeded at startup (e.g. Leeds, Sheffield, Meadowhall, Rotherham Central, Doncaster if present). The **Geofence monitor** section in Admin shows the list and map.

---

## 1. Geofence-based journey reconstruction

**Goal:** See how the system builds a journey (origin → destination) from real location events, matches it to a train, and computes a confidence score.

### Option A – Real device (phone or browser with location)

1. **Create a ticket for your user** (so the reconstructed journey is PAID):
   - In the **passenger app**, book a trip that matches a real route (e.g. Leeds → Sheffield).
   - Complete reserve → payment → confirm (use demo payment if configured).

2. **Trigger real location events:**
   - **On phone:** Log in to the app, allow location, and physically go to the **origin station** (or near it so GPS falls inside the geofence). Wait until the app has reported a few times (it reports every 15s). Then travel to the **destination station** (or move there / spoof location so you “arrive” there).
   - **In browser:** Use the same app in a browser; use DevTools → Sensors (or a location spoof extension) to set your position to the origin station’s coordinates, then after a few reports change to the destination station’s coordinates.  
   - Coordinates for geofences are in Admin → Geofence monitor (list + map); radius is typically 150 m.

3. **Check reconstruction:**
   - **Admin → Geofence monitor → Trip segments:** Find your user’s new segment (origin → destination). You should see **PAID**, **confidence score**, and **matched trip ID** when your “travel” time aligns with a scheduled departure (±5 min).
   - **Admin → Geofence monitor → Recent geofence events:** You should see ENTERED/EXITED for the stations you “visited”.

### Option B – Admin simulation (no real movement)

1. In Admin → **Geofence monitor**, use **“Simulate journey”**:
   - **User ID:** A user who already has a **CONFIRMED** ticket for that route (e.g. book Leeds → Sheffield first for that user).
   - **Origin / Destination:** Choose two different station geofences (e.g. Leeds, Sheffield).
   - **Enter origin / Enter destination:** Use times that match a real trip’s departure (e.g. if a trip departs at 14:30, use 14:28 and 14:53 so segment start is within ±5 min). Leave **accuracy** as 10 m so confidence is high.
2. Click **Simulate journey**.
3. **Check:** Trip segments table should show the new segment with **PAID**, **confidence score**, and **matched trip ID**.

### Option C – Full simulation (one click)

1. In Admin → Geofence monitor, click **“Run full simulation”**.
2. This creates user `sim-paid` with a ticket, simulates a journey with ticket (PAID + train match + confidence) and user `sim-unpaid` without a ticket (PENDING_RESOLUTION).
3. Use the result table and **Trip segments** / **Fare evasion cases** to verify journey reconstruction and confidence.

**What to verify:** A new trip segment appears with correct origin/destination, optional **matchedTripId**, and **confidenceScore** (e.g. high when GPS accuracy is good and a train is matched).

---

## 2. Automated fare evasion detection

**Goal:** See how the system flags travel without a valid ticket (PENDING_RESOLUTION) and, after the resolution window, applies a penalty (UNPAID_TRAVEL) and optional notification.

### Option A – Real device

1. **Do not** book a ticket for the test user (or use a second account with no ticket).
2. Trigger location so the app reports **exit from station A** then **entry to station B** (same as in §1: real movement or spoofed location).
3. **Check immediately:**
   - **Admin → Fare evasion cases:** New case for that user/segment with status **PENDING_RESOLUTION**.
   - **Admin → Trip segments:** Segment with **PENDING_RESOLUTION** and **resolution deadline** (e.g. 1 hour from segment creation).
4. **In the passenger app (as that user):** You may see a “no ticket” alert and options (e.g. Buy ticket / Scan QR).
5. **After the resolution window** (or after the scheduled job runs):
   - Segment can move to **UNPAID_TRAVEL** and a penalty may be applied.
   - **Admin → Fare evasion** audit logs and **Fare evasion cases** should reflect the outcome; if confidence is below threshold, penalty may be recorded but no notification sent (see `booking.journey.reconstruction.confidence-threshold`).

### Option B – Admin simulation

1. **“Simulate: no ticket Sheffield → Doncaster”** (if Doncaster geofence exists):  
   - Sets a fixed journey Sheffield → Doncaster for the given user with **no ticket**.  
   - Check **Trip segments** and **Fare evasion cases** for that user; segment should be UNPAID_TRAVEL or PENDING_RESOLUTION depending on job run.

2. **“Simulate journey”** with a user who has **no** ticket:  
   - User ID = a user with no CONFIRMED/PAID reservation for that route.  
   - After simulation, that user’s new segment should be **PENDING_RESOLUTION** (or UNPAID_TRAVEL once the resolution job has run).

3. **“Run full simulation”**:  
   - User `sim-unpaid` has no ticket; their segment should appear in **Fare evasion cases** as PENDING_RESOLUTION (and later UNPAID_TRAVEL after the resolution window).

**What to verify:** A segment with no matching ticket gets **PENDING_RESOLUTION**; after the resolution window and job run, it can become **UNPAID_TRAVEL** with penalty and (if confidence ≥ threshold) a notification.

---

## 3. Real-time passenger monitoring

**Goal:** See live positions and geofence activity so operators can monitor who is where and which events (ENTERED/EXITED) just happened.

### Option A – Real device

1. **Passenger app:** Log in on one or more devices (phone or browser), allow location. The app reports position every 15 seconds.
2. **Admin → Geofence monitor:**
   - **“Live user locations”** refreshes every 5 seconds. You should see each logged-in user’s last reported position (and which geofence they’re in, if any).
   - **“Recent geofence events”** lists ENTERED/EXITED with user and station. Move (or spoof) from one station to another and watch new events appear.
3. **Map:** Geofences are shown; user locations can be correlated with the list (by user ID).

### Option B – Admin-only (no passenger app)

1. **“Simulate geofence event”:**  
   - Choose **User ID**, **Geofence** (station), and **Event type** (ENTERED or EXITED).  
   - Submit. The event is stored and published; pipeline runs (warnings, segment creation, fare checks).
2. **Refresh data** and check **Recent geofence events** and **Trip segments** to see the effect of the simulated event.
3. **“Run full simulation”** creates many events for `sim-paid` and `sim-unpaid`; after refresh you see new events and segments.

**What to verify:** User locations update in the list; geofence events appear in real time (or after refresh); you can correlate users with stations and events.

---

## Quick reference

| Use case                     | Real-life trigger                    | Admin simulation tools                                      |
|-----------------------------|--------------------------------------|-------------------------------------------------------------|
| Journey reconstruction       | App location at station A then B     | Simulate journey (with ticket); Run full simulation         |
| Fare evasion detection      | App location A→B with no ticket     | Simulate no ticket; Simulate journey (no ticket); Full sim  |
| Real-time monitoring        | App reporting location every 15s     | Simulate geofence event; Refresh Geofence monitor           |

**Config that affects behaviour:**  
- `booking.geofence.gps-min-accuracy-meters` – ignore very inaccurate fixes.  
- `booking.journey.reconstruction.train-match-window-minutes` (default 5) – train match window.  
- `booking.journey.reconstruction.confidence-threshold` (default 85) – only send penalty notification when confidence ≥ this.  
- `booking.fare-evasion.resolution-window-minutes` (default 60) – time to resolve before penalty.

---

## 4. Simulation with large dataset (automated)

**Goal:** Measure detection accuracy and false positive rate with 170 simulated journey scenarios.

**Run:** `cd booking-service && mvn test -Dtest=SimulatedJourneyDetectionTest`

**Distribution:**

| Scenario        | Tests | Expected outcome |
|----------------|-------|-------------------|
| Valid journeys | 50    | Segment PAID, no fraud alert |
| No ticket      | 40    | Segment PENDING_RESOLUTION/UNPAID, NO_TICKET alert |
| Over travel    | 40    | UNDERPAID or OVER_TRAVEL alert (ticket Leeds→Meadowhall, journey Leeds→Sheffield) |
| Route violation| 20    | WRONG_ROUTE/ROUTE_VIOLATION or PENDING (ticket Leeds→Sheffield, journey Sheffield→Rotherham) |
| Ticket sharing | 20    | TICKET_SHARING in audit (same reservation, two passengers, overlapping segments) |

**Report:** The test prints **False Positive Rate (FPR)** for valid journeys and **True Positive Rate (TPR)** for fraud scenarios, and asserts FPR ≤ 10%, TPR ≥ 85%. Example: `Valid journeys: 50/50 correct (FPR = 0.00%); Fraud scenarios: 117/120 correct (TPR = 97.50%)`.

For test strategy and case IDs, see [TEST-STRATEGY-AND-CASES.md](TEST-STRATEGY-AND-CASES.md) §3 Integration (I-B-13).

---

For more detail on the algorithm and accuracy, see [JOURNEY-RECONSTRUCTION.md](JOURNEY-RECONSTRUCTION.md) and [GEOFENCE-ADMIN.md](GEOFENCE-ADMIN.md).

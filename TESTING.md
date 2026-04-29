# How to test RailBook

For a **combined test strategy** (unit → integration → system → acceptance → performance → security → regression → compatibility → chaos) and concrete test cases for the whole system, see **[docs/TEST-STRATEGY-AND-CASES.md](docs/TEST-STRATEGY-AND-CASES.md)**.

## 1. Start backend and frontend

**Terminal 1 – Backend**
```bash
cd booking-service
mvn spring-boot:run
```
API: http://localhost:8080

**Terminal 2 – Frontend**
```bash
npm install
npm start
```
App: http://localhost:3000

Admin secret is set in `booking-service/src/main/resources/application.yml` under `booking.admin.secret` (default: `admin123`).

---

## 2. Test geofence + “no ticket” flow (automation)

When a user **enters a station** and has **no CONFIRMED ticket** from that station, the system creates a pending station-entry action and notifies them. The app shows a modal: **Buy ticket** | **Ignore** | **I have a ticket (scan QR)**.

### Option A – Trigger via admin “record geofence event”

1. **Ensure geofences exist**  
   Call (or use admin UI):
   ```bash
   curl -s -X GET "http://localhost:8080/api/admin/geofences" -H "X-Admin-Secret: admin123"
   ```
   If the list is empty, create one (e.g. Sheffield):
   ```bash
   curl -s -X POST "http://localhost:8080/api/admin/geofences" \
     -H "X-Admin-Secret: admin123" -H "Content-Type: application/json" \
     -d '{"name":"Sheffield Station","stationName":"Sheffield","latitude":53.38,"longitude":-1.47,"radiusMeters":100}'
   ```
   Note the returned `id` (e.g. `1`).

2. **Log in as a user in the app** (e.g. register or log in). Remember the user ID (e.g. from browser devtools / network or from the backend; often a numeric string).

3. **Simulate that user entering the geofence** (replace `USER_ID` and `GEOFENCE_ID`):
   ```bash
   curl -s -X POST "http://localhost:8080/api/admin/geofence-events" \
     -H "X-Admin-Secret: admin123" -H "Content-Type: application/json" \
     -d '{"userId":"USER_ID","geofenceId":GEOFENCE_ID,"eventType":"ENTERED"}'
   ```

4. **In the app**  
   Refresh or wait for the next load of pending actions. You should see the **“No ticket detected”** modal with the three options. Try **Ignore**, **Buy a ticket**, or **I have a ticket** and enter a reservation ID if you have one.

### Option B – Trigger via user location (real flow)

1. Log in in the app.
2. Allow location when prompted (or mock it in devtools).
3. The app reports location periodically. Ensure a geofence exists that contains your (or mocked) coordinates.
4. When the backend detects ENTERED for that geofence and the user has no ticket from that station, the pending action is created and the modal appears in the app.

### Check pending actions via API

```bash
# As the user (replace USER_ID with the value the app sends, e.g. in X-User-Id)
curl -s "http://localhost:8080/api/station-entry-actions/pending" -H "X-User-Id: USER_ID"
```

---

## 3. Test “I have a ticket” (Scan QR / reservation ID)

1. Create a **CONFIRMED** booking for a user (reserve → payment → confirm in the app). Note the **reservation ID** (e.g. from My bookings or network).
2. Trigger a “no ticket” station entry for that same user (Option A or B above).
3. In the modal, choose **I have a ticket (scan QR)**.
4. Enter the **reservation ID** and click **Validate**. The action should complete and the modal close.

API equivalent (replace IDs and user):
```bash
curl -s -X POST "http://localhost:8080/api/station-entry-actions/ACTION_ID/validate-qr" \
  -H "X-User-Id: USER_ID" -H "Content-Type: application/json" \
  -d '{"reservationId":RESERVATION_ID}'
```

---

## 4. Load tests (concurrent geofence entries and QR validations)

These endpoints simulate many users entering a geofence at once and many QR validations at once. They require **X-Admin-Secret**.

### 4.1 Concurrent geofence entries

Simulate hundreds of users entering the same geofence:

```bash
# Replace 1 with your geofence ID; 300 = number of concurrent “users”
curl -s -X POST "http://localhost:8080/api/admin/load-test/geofence-entries?geofenceId=1&concurrentUsers=300" \
  -H "X-Admin-Secret: admin123" -H "Content-Type: application/json"
```

Example response:
```json
{
  "successCount": 300,
  "failureCount": 0,
  "totalRequested": 300,
  "errors": []
}
```

- User IDs are `load-user-1` … `load-user-N`. Each gets at most one pending station-entry action per geofence (idempotent).

### 4.2 Concurrent QR validations

Simulate many ticket validations at the same time. You need **existing** pending actions and CONFIRMED reservations for those users.

1. **Create pending actions** (e.g. run load-test geofence-entries above so you have many `load-user-*` with pending actions).
2. **Create CONFIRMED reservations** for those users (e.g. via booking flow or seed script). Then note `actionId`, `userId`, and `reservationId` for each.
3. **Call the load-test** with a JSON array (max 500 items):

```bash
curl -s -X POST "http://localhost:8080/api/admin/load-test/validate-qr" \
  -H "X-Admin-Secret: admin123" -H "Content-Type: application/json" \
  -d '[
    {"actionId":1,"userId":"load-user-1","reservationId":101},
    {"actionId":2,"userId":"load-user-2","reservationId":102}
  ]'
```

Response shape: `successCount`, `failureCount`, `totalRequested`, `errors`. Under concurrency, only one validation per (action, reservation) should succeed; double-use is prevented by the backend.

---

## 5. Run backend tests

**Integration tests** (implement TESTING.md §2–§4 and §6):

```bash
cd booking-service
mvn test -Dtest=RailBookTestingMdIntegrationTest
```

These tests use the `test` profile (in-memory H2, no Kafka, `application-test.yml`) and seed one geofence, one trip with four seats and fare buckets (ADVANCE_1, ADVANCE_2, ANYTIME). They cover:

- **§2 Geofence + no ticket:** Record ENTERED for a user without a ticket → GET pending actions returns one.
- **§3 Validate QR:** Register → reserve → demo payment → trigger no-ticket entry → validate-qr with reservation ID → pending list empty.
- **§4.1 Load test:** POST load-test/geofence-entries (50 users) → successCount 50, failureCount 0.
- **§6 Payment flow:** Reserve → create-payment-intent (demo) → reservation becomes CONFIRMED.
- **Dynamic pricing (Drools):** Reserve with fare buckets present → reservation amount is one of the bucket prices (8, 9, or 10).
- **Double booking:** User A reserves a seat → User B reserves the same seat → second request returns 400, no reservation for B.

Run **all** backend tests (including any other unit tests):

```bash
cd booking-service
mvn test
```

---

## Quick reference – main APIs

| What              | Method | Endpoint |
|-------------------|--------|----------|
| Record geofence   | POST   | `/api/admin/geofence-events` (body: `userId`, `geofenceId`, `eventType`: ENTERED/EXITED) |
| Pending actions   | GET    | `/api/station-entry-actions/pending` (header: `X-User-Id`) |
| Respond (choice)  | POST   | `/api/station-entry-actions/:id/respond` (body: `{"choice":"IGNORE"\|"BUY_TICKET"\|"SCAN_QR"}`) |
| Validate QR       | POST   | `/api/station-entry-actions/:id/validate-qr` (body: `{"reservationId":...}`) |
| Load: geofence    | POST   | `/api/admin/load-test/geofence-entries?geofenceId=1&concurrentUsers=300` |
| Load: QR          | POST   | `/api/admin/load-test/validate-qr` (body: array of `{actionId, userId, reservationId}`) |

All admin endpoints need header: `X-Admin-Secret: admin123` (or your configured value).

---

## 6. Payment flow (Stripe: Visa, Apple Pay)

Payment is **never trusted from the client**. The backend verifies payment via **webhook** only.

### Ticket state machine

| Status | Description |
|--------|-------------|
| RESERVED / PENDING_PAYMENT | Temporary booking, not paid |
| PAYMENT_PROCESSING | User at gateway; payment in progress |
| PAID | Payment confirmed by gateway (webhook) |
| CONFIRMED | Ticket valid for travel |
| CANCELLED | Payment failed or expired |
| USED | Ticket used for travel |

### Flow

1. User reserves seats → status **RESERVED**.
2. On payment step, user chooses **Pay with Visa / Apple Pay** or **Pay with test reference (demo)**.
3. **Visa / Apple Pay**: Frontend calls `POST /reservations/:id/create-payment-intent` with `{ "gateway": "STRIPE" }`. Backend creates a Stripe PaymentIntent, sets status **PAYMENT_PROCESSING**, returns `clientSecret`. User completes payment at Stripe (Stripe.js Elements or redirect). Stripe sends **webhook** `payment_intent.succeeded` to `POST /webhooks/stripe`.
4. **Webhook**: Backend verifies **signature**, checks **amount** and **currency** (GBP), then sets reservation **PAID** then **CONFIRMED**. Duplicate webhooks are **idempotent** (already PAID/CONFIRMED → no-op). Amount mismatch → **CANCELLED**.
5. **Demo**: User clicks “Pay with test reference (demo)” → backend sets PAID with a generated reference (no gateway).

### Configure Stripe

In `booking-service/src/main/resources/application.yml` or environment:

- `stripe.api-key`: Stripe secret key (e.g. `sk_test_...`). If empty, “Visa / Apple Pay” is hidden.
- `stripe.webhook-secret`: Webhook signing secret (`whsec_...`) from Stripe Dashboard → Webhooks.

**Local webhook testing:**

```bash
stripe listen --forward-to localhost:8080/webhooks/stripe
```

Use the printed `whsec_...` as `stripe.webhook-secret`. Then trigger a test payment; Stripe will forward events to your backend.

### Validation scenarios

| Scenario | Expected result |
|----------|-----------------|
| Payment successful | Ticket → PAID then CONFIRMED |
| Payment failed | Ticket → CANCELLED (webhook `payment_intent.payment_failed`) |
| Payment cancelled | Ticket → CANCELLED |
| Duplicate webhook | Ticket remains PAID/CONFIRMED (idempotent) |
| Payment amount mismatch | Ticket → CANCELLED, not PAID |
| Geofence / boarding | Only allow if ticket status CONFIRMED (or PAID) and not USED |

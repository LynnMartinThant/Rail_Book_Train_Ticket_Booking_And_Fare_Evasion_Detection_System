# RailBook – Train Ticket Booking System

Fully working train ticket booking with **double-booking prevention**, following the workflow:

**User flow:** Reserve Seat → Payment → Confirm Booking

**Architecture:** Client (React) → Load Balancer → Spring Boot Booking Service → ACID Database

## Tech Stack

| Layer | Technology |
|-------|------------|
| Frontend | React 19 + Tailwind CSS |
| Backend | Spring Boot 3.2 (Java 17) |
| Database | H2 (ACID-compliant, file-based) |
| Architecture | Microservice + policy-driven |

## How double booking is prevented

- **ACID transactions**: All reserve/payment/confirm operations run in `@Transactional` boundaries.
- **Pessimistic locking**: When reserving a seat, the backend locks the corresponding `TripSeat` row with `PESSIMISTIC_WRITE` (SELECT FOR UPDATE), so two users cannot reserve the same seat concurrently.
- **Status and expiry**: Reservations have statuses (`RESERVED` → `PAID` → `CONFIRMED`) and an expiry time (configurable, default 15 minutes). Only one active (non-expired) reservation per seat per trip is allowed; the lock plus a check for existing active reservations enforces this.

## Project layout

```
frontend/
├── src/                    # React app (client)
│   ├── api/client.js       # API client for booking service
│   ├── components/         # TripList, SeatSelect, Payment, Confirmation
│   └── App.js
├── booking-service/        # Spring Boot microservice
│   ├── src/main/java/com/train/booking/
│   │   ├── api/            # REST controller, DTOs
│   │   ├── config/         # Policy properties, data init
│   │   ├── domain/         # Train, Trip, Seat, TripSeat, Reservation
│   │   ├── repository/    # JPA repositories
│   │   └── service/       # BookingService, BookingPolicyService, TripQueryService
│   └── pom.xml
├── nginx.conf              # Load balancer / reverse proxy example
├── vercel.json             # Vercel: SPA rewrites
└── README.md
```

## Deploy on Vercel

The **React frontend** can be deployed on [Vercel](https://vercel.com) in a few steps. The **backend (booking-service)** is not deployed by Vercel; deploy it separately (e.g. [Railway](https://railway.app), [Render](https://render.com), or a VPS) and point the frontend to its URL.

1. **Push your repo to GitHub** (if not already).

2. **Import the project on Vercel**
   - Go to [vercel.com](https://vercel.com) → **Add New** → **Project**.
   - Import your Git repository. Set the **Root Directory** to the folder that contains `package.json` and `src/` (this project root).
   - Vercel will detect Create React App. **Build Command:** `npm run build`, **Output Directory:** `build` (defaults are fine).

3. **Set the backend API URL**
   - In the Vercel project, go to **Settings** → **Environment Variables**.
   - Add: **Name** `REACT_APP_API_URL`, **Value** your backend API base URL, e.g. `https://your-booking-api.railway.app/api` (no trailing slash). Use the same URL you use for the browser (including `/api`).
   - Redeploy so the variable is applied.

4. **Deploy**
   - Click **Deploy**. The first build runs automatically. Future pushes to your main branch will trigger new deployments.

**Notes**

- `vercel.json` in this repo adds SPA rewrites so routes like `/admin` work when refreshed.
- `.vercelignore` excludes `booking-service`, `android`, and `ios` to keep deploys fast.
- If you haven’t deployed the backend yet, the live site will show “Booking service not reachable” until `REACT_APP_API_URL` points to a running API.

**Works on localhost but not on Vercel?**

| Check | What to do |
|-------|------------|
| **Backend URL** | In Vercel → **Settings** → **Environment Variables**, set `REACT_APP_API_URL` to your **deployed** backend URL (e.g. `https://your-app.railway.app/api`). Without this, the app calls `/api` on the Vercel domain, where there is no API. |
| **Backend deployed?** | Deploy the Java backend (e.g. [Railway](https://railway.app), [Render](https://render.com)). The backend allows CORS from `https://*.vercel.app`, so your Vercel site can call it once the URL is set. |
| **Redeploy after env change** | After adding or changing `REACT_APP_API_URL`, trigger a new deployment (Deployments → … → Redeploy) so the build picks up the variable. |

## Deploy backend on Render

Deploy the **booking-service** (Java/Spring Boot) on [Render](https://render.com) so your Vercel frontend can call it.

1. **Push your repo to GitHub** (if not already).

2. **Create a Web Service on Render**
   - Go to [dashboard.render.com](https://dashboard.render.com) → **New** → **Web Service**.
   - Connect your GitHub repo (the one that contains `booking-service`).

3. **Configure the service**
   - **Name:** e.g. `railbook-api` (or any name).
   - **Region:** choose one close to you.
   - **Root Directory:** set to **`booking-service`** (required — your repo root is the frontend).
   - **Runtime:** **Java**.
   - **Build Command:** `mvn clean package -DskipTests`
   - **Start Command:** `java -jar target/booking-service-1.0.0.jar`
   - **Instance type:** Free (or paid for always-on).

4. **Environment (optional)**
   - **PostgreSQL (recommended on Render):** Add a **PostgreSQL** database in Render (Dashboard → New → PostgreSQL), then in the **Web Service** → **Environment** add **`DATABASE_URL`** = the **Internal Database URL** from the PostgreSQL service (e.g. `postgresql://user:pass@host:5432/dbname`). The app will use the same schema and seed data (trains, trips, geofences) via JPA and `DataInitializer`. See [PostgreSQL (same data)](#postgresql-same-data) below.
   - Without PostgreSQL, the app uses H2 file storage; on Render’s free tier the filesystem is ephemeral, so data can reset on deploy.
   - You can set `BOOKING_DATA_RESET_ON_STARTUP` = `false` if you don’t want to re-seed data on every deploy.

5. **Deploy**
   - Click **Create Web Service**. Render will build and start the app.
   - When it’s live, copy the service URL (e.g. `https://railbook-api.onrender.com`).

6. **Point Vercel at the backend**
   - In your Vercel project → **Settings** → **Environment Variables**.
   - Set **`REACT_APP_API_URL`** = `https://your-render-service.onrender.com/api` (your Render URL + `/api`, no trailing slash).
   - Redeploy the Vercel frontend.

**Notes**

- The backend reads **`PORT`** from the environment (Render sets it automatically); local default remains 8080.
- First request on the free tier can be slow (service may spin down when idle). For production, use a paid instance or another host.

## PostgreSQL (same data)

The backend supports **PostgreSQL** with the same JPA entities and seed data (trains, trips, stations, geofences, fare buckets). No code changes are required.

### On Render

1. In the Render dashboard, create a **PostgreSQL** database (New → PostgreSQL).
2. In your **Web Service** (booking-service) → **Environment**, add:
   - **Key:** `DATABASE_URL`
   - **Value:** the **Internal Database URL** from the PostgreSQL service (copy from the PostgreSQL service’s “Info” tab; format `postgresql://user:password@hostname:5432/database`).
3. Redeploy the Web Service. The app will connect to PostgreSQL, run `ddl-auto: update` to create/update tables, and run the same `DataInitializer` and `FareBucketInitializer` / `GeofenceDataInitializer` so you get the same trains, trips, and data as with H2.

### Local PostgreSQL

1. Install and start PostgreSQL, create a database (e.g. `railbook`).
2. Set `DATABASE_URL` and run the app, e.g.:

   ```bash
   export DATABASE_URL="postgresql://postgres:yourpassword@localhost:5432/railbook"
   cd booking-service
   mvn spring-boot:run
   ```

   Or set **`SPRING_DATASOURCE_URL`**, **`SPRING_DATASOURCE_USERNAME`**, **`SPRING_DATASOURCE_PASSWORD`** and activate the `postgres` profile:

   ```bash
   export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/railbook"
   export SPRING_DATASOURCE_USERNAME=postgres
   export SPRING_DATASOURCE_PASSWORD=yourpassword
   mvn spring-boot:run -Dspring-boot.run.profiles=postgres
   ```

3. With `booking.data.reset-on-startup: true` (default), the app will (re)create and seed the same data on startup.

If `DATABASE_URL` is not set, the app uses **H2** (file-based) as before.

## Open and run in Android Studio

You can open this project in **Android Studio** and compile/run the backend without changing any code.

1. **Open project**: In Android Studio choose **File → Open** and select the **project root folder** (the folder that contains `pom.xml` and `booking-service`). Android Studio will detect the root Maven POM and import the project.
2. **JDK**: Use **JDK 17** (Booking → Build, Execution, Deployment → Build Tools → Maven, or File → Project Structure → Project SDK). The backend requires Java 17.
3. **Build**: **Build → Build Project** (or Ctrl+F9 / Cmd+F9). Maven will compile `booking-service`.
4. **Run**: In the Maven tool window, open **frontend-root → booking-service → Plugins → spring-boot**, then double‑click **spring-boot:run**. Or add a Run Configuration: Main class `com.train.booking.BookingServiceApplication` (if present) or use the Maven goal `spring-boot:run` for module `booking-service`.

The React frontend (`src/`, `package.json`) stays in the same repo; run it from a terminal with `npm install` and `npm start` as usual. Android Studio is used here only for the Java backend.

## Quick start

### 1. Backend (Booking Service)

Requires **Java 17** and **Maven** (or add the [Maven Wrapper](https://maven.apache.org/wrapper/) and use `./mvnw`).

```bash
cd booking-service
mvn spring-boot:run
```

- API: [http://localhost:8080](http://localhost:8080)
- H2 console: [http://localhost:8080/h2-console](http://localhost:8080/h2-console) (JDBC URL: `jdbc:h2:file:./data/bookingdb`)

The app seeds sample trains, trips, and seats on first run.

### 2. Frontend (React)

```bash
npm install
npm start
```

- App: [http://localhost:3000](http://localhost:3000)
- In development, the React app proxies `/api` to `http://localhost:8080` (see `setupProxy.js`).

**Chrome device toolbar (responsive):** If the app works normally but **fails to fetch** when you open DevTools and switch to device emulation (e.g. iPhone 14), open the app at **http://127.0.0.1:3000** instead of `localhost:3000`. Chrome can drop requests to `localhost` in that mode; using `127.0.0.1` avoids it. The app also retries trip fetches once automatically.

### 3. Mobile app (Capacitor)

The same React app runs as a native Android and iOS app via [Capacitor](https://capacitorjs.com).

**Important:** Run all Capacitor commands (`npx cap ...`, `npm run cap:...`) from **this project root** — the folder that contains `package.json`, `capacitor.config.ts`, and the `android/` folder. If you see *"android platform has not been added yet"*, you are likely in the wrong directory (e.g. the repo root or `booking-service`). `cd` into the frontend project folder first, then run the command again. To add Android from scratch: `npx cap add android` (from this folder).

**Prerequisites**

- Backend running: `cd booking-service && mvn spring-boot:run`
- **Android:** [Android Studio](https://developer.android.com/studio) and JDK 17. Open the `android` folder in Android Studio to build/run.
- **iOS:** macOS with [Xcode](https://developer.apple.com/xcode/) and CocoaPods (`sudo gem install cocoapods`).

**Build and run**

1. Build the web app and sync to native projects:
   ```bash
   npm run build
   npx cap sync
   ```
2. Run on a device or emulator:
   - **Android:** `npx cap open android` (opens Android Studio), then Run ▶. Or from CLI: `npx cap run android`
   - **iOS:** `npx cap open ios` (opens Xcode), then Run ▶. Or: `npx cap run ios`

**API URL for mobile**

The app talks to your backend. By default it uses:

- **Android emulator:** `http://10.0.2.2:8080/api` (host machine)
- **iOS simulator:** `http://localhost:8080/api`
- **Real device:** Set `REACT_APP_API_URL` when building, e.g. `REACT_APP_API_URL=http://192.168.1.100:8080/api npm run build`, then `npx cap sync`. Use your computer’s LAN IP.

See `.env.capacitor.example` for examples. The backend allows `capacitor://localhost` in CORS so the native app can call the API.

**Shortcuts**

- `npm run cap:sync` — build and sync in one step
- `npm run cap:android` — build, sync, and open Android Studio
- `npm run cap:ios` — build, sync, and open Xcode

**Performance (app feels slow)**

- Always run a **production build** on device: `npm run build` then `npx cap sync`. Do not use live reload (`server.url` in `capacitor.config.ts`) for normal use — that loads the dev bundle and is much slower.
- The admin UI (and its map library) is loaded only when you open `/admin`, so the main booking app should start with a smaller bundle. Rebuild and sync after pulling changes if you notice slowness.

**Troubleshooting**

- *"android platform has not been added yet"* → Run the command from the **frontend** folder (where `capacitor.config.ts` and `android/` live), not from the repo root or `booking-service`. Then run `npx cap add android` if the `android/` folder is missing.
- *Android build fails (Gradle / Java)* → Open the `android` folder in Android Studio and use **File → Sync Project with Gradle Files**. Ensure JDK 17 is installed and selected in **File → Project Structure → SDK Location**.
- *"Fail to fetch" / "Booking service not reachable" on the Android app* → (1) Start the backend on the **same machine** as the emulator: `cd booking-service && mvn spring-boot:run`. (2) On your computer’s browser open **http://localhost:8080/api/trips** — you should see JSON. If that fails, the backend isn’t running or the port is wrong. (3) Rebuild and sync the app: `npm run build && npx cap sync`, then run the app again. The app uses **http://10.0.2.2:8080/api** from the emulator (10.0.2.2 is the host from the emulator’s perspective). If the backend still can’t be reached, in `application.yml` set `server.address: 0.0.0.0` under `server:` and restart the backend.

### 4. Using the app

1. **Create an account** or **log in** (email + password). User identity is stored in the browser and sent as `X-User-Id` for all booking API calls.
2. **Select a journey** from the list of trips.
3. **Choose seats** on the seat map (green = available, grey = booked).
4. **Reserve** – seats are held for the configured time (e.g. 1 minute in demo).
5. **Payment** – enter a payment reference (e.g. `PAY-12345`).
6. **Confirm** – finalise the booking.
7. **My bookings** – view all your reservations (reserved, paid, confirmed) from the header link.

### No trips showing? Reset the database

The app seeds **20 trains** and **40 trips** only when the database is empty. If you see “No trips available”:

1. **Option A – Reset on startup (easiest)**  
   In `booking-service/src/main/resources/application.yml`, set:
   ```yaml
   booking:
     data:
       reset-on-startup: true
   ```
   Restart the backend (`cd booking-service && mvn spring-boot:run`). It will wipe the DB and create 20 trains and 40 trips. Then set `reset-on-startup` back to `false` so it doesn’t wipe on every restart.

2. **Option B – Delete the DB file**  
   Stop the backend, then from the **project root**:
   ```bash
   rm -rf booking-service/data   # DB when you run backend from booking-service/
   rm -rf data                   # DB when you run backend from project root (e.g. mvn -f booking-service/pom.xml spring-boot:run)
   ```
   Start the backend again from `booking-service`: `cd booking-service && mvn spring-boot:run`.

## Policy-driven configuration

Backend behaviour is driven by `booking.policy` in `booking-service/src/main/resources/application.yml`:

- `reservation-timeout-minutes`: how long a reservation stays valid (default 15).
- `max-seats-per-booking`: max seats per reservation (default 10).
- `allowed-reservation-statuses-for-payment`: e.g. `RESERVED`.
- `allowed-payment-statuses-for-confirm`: e.g. `PAID`.

Changing these values alters reservation expiry and allowed transitions without code changes.

## Load balancer

The repo includes an example **nginx** config (`nginx.conf`) that:

- Serves the React frontend (static files).
- Proxies `/api/` to the Spring Boot booking service(s).

For multiple instances, define an `upstream` with several `server` entries (e.g. `booking-service:8080`, `booking-service-2:8080`). In production, put this nginx (or another load balancer) in front of the app and backend:

```
Client (Web/App) → Load Balancer (e.g. nginx) → Spring Boot Booking Service → Database
```

## API summary

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/register` | Create account (body: `{ "email", "password" }`, min 6 chars) |
| POST | `/api/auth/login` | Log in (body: `{ "email", "password" }`) → returns `{ "userId", "email" }` |
| GET | `/api/trips` | List trips |
| GET | `/api/trips/{id}/seats` | List seats and availability for a trip |
| GET | `/api/bookings` | List current user's reservations (requires `X-User-Id`) |
| POST | `/api/reserve` | Reserve seats (body: `{ "tripId", "seatIds" }`) |
| POST | `/api/reservations/{id}/payment` | Record payment (body: `{ "paymentReference" }`) |
| POST | `/api/reservations/{id}/confirm` | Confirm booking |
| GET | `/api/reservations/{id}` | Get reservation (with `X-User-Id`) |

All booking endpoints (except GET trips/seats) require header: `X-User-Id: <user-id>` (set by the app after login).

**Geofence admin:** For polygon boundaries, platform segmentation, GPS noise filtering, event debouncing, ticket validation, false positive monitoring, calibration, and system health, see [docs/GEOFENCE-ADMIN.md](docs/GEOFENCE-ADMIN.md) (implementation and use-case scenarios).

**Testing use cases in real life:** To test geofence-based journey reconstruction, automated fare evasion detection, and real-time passenger monitoring (with a real device or admin simulation), see [docs/TESTING-USE-CASES.md](docs/TESTING-USE-CASES.md).

**Fraud Detection Engine & three engines:** Geofencing, Journey Reconstruction, and Fraud Rules (Drools), plus the Fraud Detection Engine (ticket sharing, refund fraud, multi-device, chargeback, suspicious account, system reliability), see [docs/FRAUD-AND-ENGINES.md](docs/FRAUD-AND-ENGINES.md).

**Test strategy & test cases:** Combined unit, integration, system, acceptance, performance, security, regression, compatibility, and chaos test cases for the whole system: [docs/TEST-STRATEGY-AND-CASES.md](docs/TEST-STRATEGY-AND-CASES.md).

### Verify Drools (pricing rules)

To confirm the Drools pricing engine is working:

1. **Admin endpoint** (backend must be running, use your admin secret):
   ```bash
   curl -s -H "X-Admin-Secret: YOUR_ADMIN_SECRET" http://localhost:8080/api/admin/drools-status
   ```
   - `{ "status": "ok", "message": "Pricing rules loaded and executed successfully." }` → Drools is working.
   - `{ "status": "unavailable", "message": "..." }` → Check backend logs for Drools init/execution errors.

2. **Logs**: When you search for trips (e.g. open the app and load the trip list), the backend logs **"Pricing via Drools: tripId=… tier=… price=…"** for each trip that used the rule engine. If you see **"Pricing Drools failed, using fallback"** or no "Pricing via Drools" lines, Drools init or execution failed (fallback pricing is still applied).

3. **Trip list**: Trips returned by `GET /api/trips` include a `fareTier` (e.g. `ADVANCE_1`, `OFF_PEAK`, `ANYTIME`) when dynamic pricing is used; rules close tiers by occupancy, peak hours, and advance-window.

## Testing 3 users booking the same seat at once

To verify double-booking prevention, run three reserve requests for the **same seat** at the same time. Only one should succeed; the others should get a conflict.

### Automated script (recommended)

With the **backend running** on port 8080:

```bash
node scripts/test-concurrent-booking.js
```

The script will: fetch a trip and an available seat, then send 3 concurrent `POST /api/reserve` requests (same `tripId` and `seatId`, different `X-User-Id`). It exits with success only if **exactly one** request succeeds and two fail with a conflict.

If the API is not on the default host/port:

```bash
API_BASE=http://localhost:8080/api node scripts/test-concurrent-booking.js
```

### Manual test with curl

Pick a `tripId` and `seatId` from the API (e.g. `GET /api/trips` then `GET /api/trips/1/seats`). Then run three reserves in parallel:

```bash
# Replace 1 and 1 with your tripId and seatId
for u in user-1 user-2 user-3; do
  curl -s -X POST http://localhost:8080/api/reserve \
    -H "Content-Type: application/json" -H "X-User-Id: $u" \
    -d '{"tripId":1,"seatIds":[1]}' &
done; wait
```

Check the responses: one should be a list of reservations (200); the others should be 409 or 400 with a message like "already booked or reserved".

### Manual test in the browser

1. Open the app in 3 windows/tabs.
2. Use different users per tab: in the browser console set a different user before reserving, or run the app with different `REACT_APP_USER_ID` in each (e.g. three terminals with `REACT_APP_USER_ID=user-1 npm start` etc., each on a different port if needed).
3. In all three, go to the same trip and select the **same seat**.
4. Click **Reserve** in all three at roughly the same time. One should succeed; the others should show an error (e.g. "Seat … is already booked or reserved").

## Mobile app wrapper (PWA + Capacitor)

The frontend is set up to run as an **installable PWA** or inside a **native app shell** (Capacitor).

### PWA (Progressive Web App)

- **Add to Home Screen**: On mobile browsers (iOS Safari, Android Chrome), use “Add to Home Screen” to install. The app opens in **standalone** mode (no browser UI).
- **Manifest**: `public/manifest.json` defines name (RailBook), icons, `display: standalone`, and `theme_color`.
- **Meta tags**: `viewport-fit=cover`, `apple-mobile-web-app-capable`, and safe-area support so content clears the notch and home indicator.
- **Safe area**: `src/index.css` uses `env(safe-area-inset-*)` so layout is not clipped on notched devices.

No extra build step; use `npm run build` and deploy the `build/` folder. Serve over HTTPS for “Add to Home Screen” to be offered.

### Capacitor (iOS / Android native wrapper)

The same React app can be wrapped as a native app and built for App Store / Play Store.

1. **Install dependencies** (already in `package.json`):
   ```bash
   npm install
   ```

2. **Build the web app** and add native projects (one-time):
   ```bash
   npm run build
   npx cap add ios
   npx cap add android
   ```

3. **Sync and open in IDE**:
   - **iOS:** `npm run cap:ios` (builds, syncs, opens Xcode). Requires macOS and Xcode.
   - **Android:** `npm run cap:android` (builds, syncs, opens Android Studio).

4. **API URL when running in the app**: The app loads from `build/` (or from a URL if you use `server.url` in `capacitor.config.ts`). API calls use `REACT_APP_API_URL` from the **build** that was run. For the native app to talk to your backend, build with the correct API base:
   ```bash
   REACT_APP_API_URL=https://your-api.example.com/api npm run build
   npx cap sync
   ```
   Then run from Xcode or Android Studio. Without this, the app will try to use `/api` (same origin), which only works if you serve the app and proxy from the same host.

**Config**: `capacitor.config.ts` sets `appId: com.railbook.app`, `appName: RailBook`, `webDir: build`. Change `appId` if you need a different bundle/application ID.

## Build for production

- **Frontend:** `npm run build` → `build/`
- **Backend:** `cd booking-service && mvn -DskipTests package` → `target/booking-service-1.0.0.jar`  
  Run with: `java -jar target/booking-service-1.0.0.jar`

Use the same policy and DB settings (or switch to PostgreSQL) and put both behind your load balancer as described above.

import { Capacitor } from '@capacitor/core';

function getApiBase() {
  if (process.env.REACT_APP_API_URL) return process.env.REACT_APP_API_URL;
  if (typeof Capacitor !== 'undefined' && Capacitor.isNativePlatform()) {
    return Capacitor.getPlatform() === 'android'
      ? 'http://10.0.2.2:8080/api'
      : 'http://localhost:8080/api';
  }
  // WebView loaded from capacitor:// or file:// (e.g. Android): use emulator host
  const p = typeof window !== 'undefined' ? window.location?.protocol : '';
  if (p === 'capacitor:' || p === 'file:') {
    return 'http://10.0.2.2:8080/api';
  }
  return '/api';
}

let _apiBase;
function apiBase() {
  if (_apiBase == null) _apiBase = getApiBase();
  return _apiBase;
}

function getBackendUnreachableMsg() {
  const base = apiBase();
  const urlHint = base.startsWith('http') ? ` (app is using: ${base.replace(/\/api\/?$/, '')})` : '';
  return `Booking service not reachable. Start it: cd booking-service && mvn spring-boot:run${urlHint}`;
}

const DEVICE_TOOLBAR_HINT =
  ' If using Chrome device toolbar (responsive), open http://127.0.0.1:3000 instead of localhost.';

const AUTH_STORAGE_KEY = 'railbook_user';

export function getStoredUser() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function setStoredUser(user) {
  if (user) localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(user));
  else localStorage.removeItem(AUTH_STORAGE_KEY);
}

function headers() {
  const user = getStoredUser();
  const userId = user?.userId || '';
  return {
    'Content-Type': 'application/json',
    ...(userId && { 'X-User-Id': userId }),
  };
}

async function parseJson(res) {
  const text = await res.text();
  if (text.trimStart().startsWith('<')) {
    throw new Error(getBackendUnreachableMsg());
  }
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    throw new Error(getBackendUnreachableMsg());
  }
}

function authError(res, data) {
  if (res.status === 404) {
    return 'Login/register not available. Restart the backend: cd booking-service && mvn spring-boot:run';
  }
  return data?.error || data?.message || res.statusText;
}

export async function login(email, password) {
  const res = await fetch(`${apiBase()}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(authError(res, data));
  return data;
}

export async function register(email, password) {
  const res = await fetch(`${apiBase()}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(authError(res, data));
  return data;
}

export async function getMyBookings() {
  const res = await fetch(`${apiBase()}/bookings`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

export async function getAdminReservations(adminSecret) {
  const res = await fetch(`${apiBase()}/admin/reservations`, {
    headers: {
      ...headers(),
      'X-Admin-Secret': adminSecret || '',
    },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    const message = body?.error || body?.message || res.statusText;
    throw new Error(message);
  }
  return parseJson(res);
}

function wrapNetworkError(err, addDeviceHint = false) {
  if (err.message === 'Failed to fetch' || err.name === 'TypeError') {
    const msg = getBackendUnreachableMsg() + (addDeviceHint ? DEVICE_TOOLBAR_HINT : '');
    return new Error(msg);
  }
  return err;
}

/** Fetch trips with one retry on network failure (helps when Chrome device toolbar causes transient failure). */
async function fetchTripsOnce(url) {
  const res = await fetch(url, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

export async function getTrips(fromStation, toStation) {
  const params = new URLSearchParams();
  if (fromStation) params.set('fromStation', fromStation);
  if (toStation) params.set('toStation', toStation);
  const qs = params.toString();
  const url = qs ? `${apiBase()}/trips?${qs}` : `${apiBase()}/trips`;
  const isLocalhost = typeof window !== 'undefined' && window.location?.hostname === 'localhost';
  try {
    try {
      return await fetchTripsOnce(url);
    } catch (e) {
      if ((e.message === 'Failed to fetch' || e.name === 'TypeError') && isLocalhost) {
        await new Promise((r) => setTimeout(r, 400));
        return await fetchTripsOnce(url);
      }
      throw e;
    }
  } catch (e) {
    throw wrapNetworkError(e, isLocalhost);
  }
}

export async function getSeats(tripId) {
  const res = await fetch(`${apiBase()}/trips/${tripId}/seats`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

export async function reserve(tripId, seatIds) {
  const res = await fetch(`${apiBase()}/reserve`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ tripId, seatIds }),
  });
  const data = await parseJson(res).catch((e) => {
    if ((e.message && e.message.includes('Booking service'))) throw e;
    return {};
  });
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

/** Available payment methods from gateway (e.g. card, apple_pay). */
export async function getPaymentMethods() {
  const res = await fetch(`${apiBase()}/payment-methods`, { headers: headers() });
  if (!res.ok) return [];
  const data = await parseJson(res).catch(() => []);
  return Array.isArray(data) ? data : [];
}

/** Create payment intent (Stripe). Returns clientSecret for Elements. Server verifies via webhook. */
export async function createPaymentIntent(reservationId, gateway = 'STRIPE') {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/create-payment-intent`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ gateway }),
  });
  const body = await parseJson(res).catch((e) => {
    if ((e.message && e.message.includes('Booking service'))) throw e;
    return {};
  });
  if (!res.ok) throw new Error(body?.error || res.statusText);
  return body;
}

export async function payment(reservationId, paymentReference) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/payment`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ paymentReference }),
  });
  const body = await parseJson(res).catch((e) => {
    if ((e.message && e.message.includes('Booking service'))) throw e;
    return {};
  });
  if (!res.ok) throw new Error(body?.error || res.statusText);
  return body;
}

export async function confirm(reservationId) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/confirm`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({}),
  });
  const body = await parseJson(res).catch((e) => {
    if ((e.message && e.message.includes('Booking service'))) throw e;
    return {};
  });
  if (!res.ok) throw new Error(body?.error || res.statusText);
  return body;
}

export async function getReservation(reservationId) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

/** Validate ticket by reservation ID (e.g. from QR). Returns { valid, message, ... }. */
export async function validateTicket(reservationId) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/validate`, { headers: headers() });
  const data = await parseJson(res).catch(() => ({}));
  return data;
}

/** Download ticket as PDF; triggers browser download. */
export async function downloadTicketPdf(reservationId) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/ticket-pdf`, { headers: headers() });
  if (!res.ok) throw new Error(res.status === 404 ? 'Ticket not found or not available for download' : await res.text());
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `ticket-${reservationId}.pdf`;
  a.click();
  URL.revokeObjectURL(url);
}

export async function reportLocation(latitude, longitude) {
  const res = await fetch(`${apiBase()}/location`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ latitude, longitude }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

export async function getMyAlerts() {
  const res = await fetch(`${apiBase()}/alerts`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

export async function markAlertRead(alertId) {
  const res = await fetch(`${apiBase()}/alerts/${alertId}/read`, {
    method: 'PATCH',
    headers: headers(),
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function requestRefund(reservationId) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/refund-request`, {
    method: 'POST',
    headers: headers(),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

export async function getMyRefundRequests() {
  const res = await fetch(`${apiBase()}/refund-requests`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

export async function getAdminAuditLogs(adminSecret, userId = null, limit = 200) {
  const params = new URLSearchParams();
  if (userId) params.set('userId', userId);
  params.set('limit', String(limit));
  const res = await fetch(`${apiBase()}/admin/audit-logs?${params}`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function getAdminRefundRequests(adminSecret) {
  const res = await fetch(`${apiBase()}/admin/refund-requests`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function adminApproveRefund(adminSecret, requestId) {
  const res = await fetch(`${apiBase()}/admin/refund-requests/${requestId}/approve`, {
    method: 'PATCH',
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function adminRejectRefund(adminSecret, requestId) {
  const res = await fetch(`${apiBase()}/admin/refund-requests/${requestId}/reject`, {
    method: 'PATCH',
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function adminAlertUserWithoutTicket(adminSecret, userId, tripId) {
  const res = await fetch(`${apiBase()}/admin/alerts`, {
    method: 'POST',
    headers: {
      ...headers(),
      'X-Admin-Secret': adminSecret || '',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ userId, tripId }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

export async function getAdminGeofences(adminSecret) {
  const res = await fetch(`${apiBase()}/admin/geofences`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function recordGeofenceEvent(adminSecret, userId, geofenceId, eventType) {
  const res = await fetch(`${apiBase()}/admin/geofence-events`, {
    method: 'POST',
    headers: {
      ...headers(),
      'X-Admin-Secret': adminSecret || '',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ userId, geofenceId, eventType }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

export async function getAdminGeofenceEvents(adminSecret, limit = 100) {
  const res = await fetch(`${apiBase()}/admin/geofence-events?limit=${limit}`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

/** Audit log entries for FARE_EVASION action. */
export async function getAdminFareEvasion(adminSecret, limit = 100) {
  const res = await fetch(`${apiBase()}/admin/fare-evasion?limit=${limit}`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

/** Fare evasion cases (PENDING_RESOLUTION + UNPAID_TRAVEL segments) for admin monitoring. */
export async function getAdminFareEvasionCases(adminSecret, limit = 100) {
  const res = await fetch(`${apiBase()}/admin/fare-evasion-cases?limit=${limit}`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function getAdminUserLocations(adminSecret) {
  const res = await fetch(`${apiBase()}/admin/user-locations`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function simulateUserNoTicket(adminSecret, userId = '4') {
  const res = await fetch(`${apiBase()}/admin/geofence-events/simulate-no-ticket?userId=${encodeURIComponent(userId)}`, {
    method: 'POST',
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

/** Simulate Doncaster → Sheffield with real-time timestamps (no ticket). enterOriginAt/enterDestinationAt = ISO-8601 instants. */
export async function simulateJourneyDoncasterSheffield(adminSecret, userId, enterOriginAt, enterDestinationAt) {
  const res = await fetch(`${apiBase()}/admin/geofence-events/simulate-journey-doncaster-sheffield`, {
    method: 'POST',
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '', 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, enterOriginAt, enterDestinationAt }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

/** Simulate journey between any two station geofences (enter origin, exit origin, enter destination). Trip segment + PENDING_RESOLUTION created. */
export async function simulateJourney(adminSecret, userId, originGeofenceId, destinationGeofenceId, enterOriginAt, enterDestinationAt) {
  const res = await fetch(`${apiBase()}/admin/geofence-events/simulate-journey`, {
    method: 'POST',
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      userId,
      originGeofenceId,
      destinationGeofenceId,
      enterOriginAt,
      enterDestinationAt,
    }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

export async function getAdminTripSegments(adminSecret, passengerId = null, limit = 100) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (passengerId) params.set('passengerId', passengerId);
  const res = await fetch(`${apiBase()}/admin/trip-segments?${params}`, {
    headers: { ...headers(), 'X-Admin-Secret': adminSecret || '' },
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

export async function sendAdminNotification(adminSecret, userId, message) {
  const res = await fetch(`${apiBase()}/admin/notifications`, {
    method: 'POST',
    headers: {
      ...headers(),
      'X-Admin-Secret': adminSecret || '',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ userId, message }),
  });
  const data = await parseJson(res).catch(() => ({}));
  if (!res.ok) throw new Error(data?.error || res.statusText);
  return data;
}

export async function getMyNotifications() {
  const res = await fetch(`${apiBase()}/notifications`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

export async function markNotificationRead(notificationId) {
  const res = await fetch(`${apiBase()}/notifications/${notificationId}/read`, {
    method: 'PATCH',
    headers: headers(),
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

/** Pending station-entry actions (no ticket at entry: Buy / Ignore / Scan QR). */
export async function getPendingStationEntryActions() {
  const res = await fetch(`${apiBase()}/station-entry-actions/pending`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

/** Respond to a pending action: choice = 'IGNORE' | 'BUY_TICKET' | 'SCAN_QR'. */
export async function respondToStationEntry(actionId, choice) {
  const res = await fetch(`${apiBase()}/station-entry-actions/${actionId}/respond`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ choice }),
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

/** Validate scanned ticket QR and complete the station-entry action. */
export async function validateTicketQr(actionId, reservationId) {
  const res = await fetch(`${apiBase()}/station-entry-actions/${actionId}/validate-qr`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ reservationId }),
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
  return parseJson(res);
}

/** Passenger's journey segments (geofence-detected travel with fare status). */
export async function getMyTripSegments(limit = 50) {
  const res = await fetch(`${apiBase()}/my/trip-segments?limit=${limit}`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  const data = await parseJson(res);
  return Array.isArray(data) ? data : [];
}

/** Current station from user's last reported location (geofence). Returns { stationName, displayName } or null. */
export async function getCurrentStation() {
  const res = await fetch(`${apiBase()}/my/current-station`, { headers: headers() });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

/** Departures from a station (future only), ordered by time. Each trip has platform (e.g. 1A, 2B). */
export async function getStationDepartures(stationName) {
  const res = await fetch(`${apiBase()}/stations/${encodeURIComponent(stationName)}/departures`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  const data = await parseJson(res);
  return Array.isArray(data) ? data : [];
}

/** Upload ticket proof to dispute a segment marked as no ticket (UNPAID_TRAVEL/UNDERPAID). */
export async function uploadTicketForSegment(segmentId, reservationId) {
  const res = await fetch(`${apiBase()}/my/trip-segments/${segmentId}/upload-ticket`, {
    method: 'POST',
    headers: headers(),
    body: JSON.stringify({ reservationId }),
  });
  if (!res.ok) {
    const data = await parseJson(res).catch(() => ({}));
    throw new Error(data?.error || res.statusText);
  }
  return parseJson(res);
}

/** Payload for ticket QR (for display or encoding). */
export async function getTicketQrPayload(reservationId) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/ticket-qr`, { headers: headers() });
  if (!res.ok) throw new Error(await res.text());
  return parseJson(res);
}

/** Cancel (release) a reservation so the seat becomes available for others. Only works for RESERVED (unpaid). */
export async function cancelReservation(reservationId) {
  const res = await fetch(`${apiBase()}/reservations/${reservationId}/cancel`, {
    method: 'POST',
    headers: headers(),
  });
  if (!res.ok) {
    const body = await parseJson(res).catch(() => ({}));
    throw new Error(body?.error || res.statusText);
  }
}

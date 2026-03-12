import React, { useState, useEffect, useCallback, useRef } from 'react';
import * as api from '../api/client';
import AdminGeofenceMap from './AdminGeofenceMap';

function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function statusBadge(status) {
  const styles = {
    RESERVED: 'bg-amber-100 text-amber-800',
    PAID: 'bg-blue-100 text-blue-800',
    CONFIRMED: 'bg-green-100 text-green-800',
    EXPIRED: 'bg-gray-100 text-gray-600',
    REFUNDED: 'bg-slate-100 text-slate-600',
  };
  const s = styles[status] || 'bg-gray-100 text-gray-700';
  return (
    <span className={`inline-flex rounded px-2 py-0.5 text-xs font-medium ${s}`}>
      {status}
    </span>
  );
}

function AdminDashboard({ secret, onLogout }) {
  const [section, setSection] = useState('overview');
  const [reservations, setReservations] = useState([]);
  const [filtered, setFiltered] = useState([]);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [trips, setTrips] = useState([]);
  const [geofenceUserId, setGeofenceUserId] = useState('');
  const [geofenceTripId, setGeofenceTripId] = useState('');
  const [alertSending, setAlertSending] = useState(false);
  const [alertSuccess, setAlertSuccess] = useState(null);
  const [auditLogs, setAuditLogs] = useState([]);
  const [auditUserId, setAuditUserId] = useState('');
  const [auditLoading, setAuditLoading] = useState(false);
  const [refundRequests, setRefundRequests] = useState([]);
  const [refundLoading, setRefundLoading] = useState(false);
  const [refundActionId, setRefundActionId] = useState(null);
  const [geofences, setGeofences] = useState([]);
  const [geofenceEvents, setGeofenceEvents] = useState([]);
  const [fareEvasionLogs, setFareEvasionLogs] = useState([]);
  const [fareEvasionCases, setFareEvasionCases] = useState([]);
  const [geofenceMonitorLoading, setGeofenceMonitorLoading] = useState(false);
  const [simUserId, setSimUserId] = useState('');
  const [simGeofenceId, setSimGeofenceId] = useState('');
  const [simEventType, setSimEventType] = useState('ENTERED');
  const [simSending, setSimSending] = useState(false);
  const [simSuccess, setSimSuccess] = useState(null);
  const [userLocations, setUserLocations] = useState([]);
  const [fareEvasionAlert, setFareEvasionAlert] = useState(null);
  const [notificationSent, setNotificationSent] = useState(null);
  const [tripSegments, setTripSegments] = useState([]);
  const [simulateNoTicketUserId, setSimulateNoTicketUserId] = useState('4');
  const [simulateNoTicketLoading, setSimulateNoTicketLoading] = useState(false);
  const [simulateNoTicketSuccess, setSimulateNoTicketSuccess] = useState(null);
  const [simulateJourneyUserId, setSimulateJourneyUserId] = useState('4');
  const [simulateJourneyOriginId, setSimulateJourneyOriginId] = useState('');
  const [simulateJourneyDestId, setSimulateJourneyDestId] = useState('');
  const [simulateJourneyEnterOrigin, setSimulateJourneyEnterOrigin] = useState('19:45');
  const [simulateJourneyEnterDest, setSimulateJourneyEnterDest] = useState('19:50');
  const [simulateJourneyLoading, setSimulateJourneyLoading] = useState(false);
  const [simulateJourneySuccess, setSimulateJourneySuccess] = useState(null);
  const [alertToUserId, setAlertToUserId] = useState('4');
  const [alertToMessage, setAlertToMessage] = useState('');
  const [alertToSending, setAlertToSending] = useState(false);
  const [alertToSuccess, setAlertToSuccess] = useState(null);
  const previousFareEvasionCount = useRef(0);

  useEffect(() => {
    api.getTrips().then((data) => setTrips(Array.isArray(data) ? data : [])).catch(() => setTrips([]));
  }, []);

  const loadAuditLogs = useCallback(async () => {
    if (!secret) return;
    setAuditLoading(true);
    try {
      const data = await api.getAdminAuditLogs(secret, auditUserId || null, 200);
      setAuditLogs(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e.message);
    } finally {
      setAuditLoading(false);
    }
  }, [secret, auditUserId]);

  const loadRefundRequests = useCallback(async () => {
    if (!secret) return;
    setRefundLoading(true);
    try {
      const data = await api.getAdminRefundRequests(secret);
      setRefundRequests(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e.message);
    } finally {
      setRefundLoading(false);
    }
  }, [secret]);

  const loadUserLocations = useCallback(async () => {
    if (!secret) return;
    try {
      const data = await api.getAdminUserLocations(secret);
      setUserLocations(Array.isArray(data) ? data : []);
    } catch {
      setUserLocations([]);
    }
  }, [secret]);

  const loadGeofenceMonitor = useCallback(async () => {
    if (!secret) return;
    setGeofenceMonitorLoading(true);
    try {
      const [gList, events, evasion, cases, locations, segments] = await Promise.all([
        api.getAdminGeofences(secret),
        api.getAdminGeofenceEvents(secret, 100),
        api.getAdminFareEvasion(secret, 100),
        api.getAdminFareEvasionCases(secret, 100),
        api.getAdminUserLocations(secret),
        api.getAdminTripSegments(secret, null, 100),
      ]);
      setGeofences(Array.isArray(gList) ? gList : []);
      setGeofenceEvents(Array.isArray(events) ? events : []);
      const newEvasion = Array.isArray(evasion) ? evasion : [];
      if (newEvasion.length > previousFareEvasionCount.current) {
        setFareEvasionAlert(`${newEvasion.length - previousFareEvasionCount.current} new fare evasion(s) detected (Sheffield → Doncaster).`);
        setTimeout(() => setFareEvasionAlert(null), 8000);
      }
      previousFareEvasionCount.current = newEvasion.length;
      setFareEvasionLogs(newEvasion);
      setFareEvasionCases(Array.isArray(cases) ? cases : []);
      setUserLocations(Array.isArray(locations) ? locations : []);
      setTripSegments(Array.isArray(segments) ? segments : []);
    } catch (e) {
      setError(e.message);
    } finally {
      setGeofenceMonitorLoading(false);
    }
  }, [secret]);

  // Auto-load geofences and map data when user opens Geofence monitor section
  useEffect(() => {
    if (section === 'geofence' && secret) {
      loadGeofenceMonitor();
    }
  }, [section, secret, loadGeofenceMonitor]);

  /* eslint-disable no-unused-vars -- kept for "Send notification" from user list */
  const handleSendNotification = async (userId, message) => {
    if (!secret) return;
    try {
      await api.sendAdminNotification(secret, userId, message);
      setNotificationSent(userId);
      setTimeout(() => setNotificationSent(null), 3000);
    } catch (e) {
      setError(e.message);
    }
  };
  /* eslint-enable no-unused-vars */

  const handleSimulateNoTicket = async (e) => {
    e.preventDefault();
    if (!secret || !simulateNoTicketUserId.trim()) return;
    setSimulateNoTicketSuccess(null);
    setSimulateNoTicketLoading(true);
    try {
      await api.simulateUserNoTicket(secret, simulateNoTicketUserId.trim());
      setSimulateNoTicketSuccess(`User ${simulateNoTicketUserId} simulated Sheffield → Doncaster (no ticket). Check trip segments and fare evasion.`);
      setTimeout(() => setSimulateNoTicketSuccess(null), 6000);
      loadGeofenceMonitor();
    } catch (e) {
      setError(e.message);
    } finally {
      setSimulateNoTicketLoading(false);
    }
  };

  const toISOAtLocalTime = (timeStr) => {
    const [h, m] = timeStr.trim().split(':').map((x) => parseInt(x, 10) || 0);
    const d = new Date();
    d.setHours(h, m, 0, 0);
    return d.toISOString();
  };

  const handleSimulateJourney = async (e) => {
    e.preventDefault();
    if (!secret || !simulateJourneyUserId.trim() || !simulateJourneyOriginId || !simulateJourneyDestId) return;
    if (Number(simulateJourneyOriginId) === Number(simulateJourneyDestId)) {
      setError('Origin and destination must be different stations.');
      return;
    }
    setSimulateJourneySuccess(null);
    setSimulateJourneyLoading(true);
    try {
      const enterOriginAt = toISOAtLocalTime(simulateJourneyEnterOrigin);
      const enterDestinationAt = toISOAtLocalTime(simulateJourneyEnterDest);
      const data = await api.simulateJourney(
        secret,
        simulateJourneyUserId.trim(),
        Number(simulateJourneyOriginId),
        Number(simulateJourneyDestId),
        enterOriginAt,
        enterDestinationAt
      );
      setSimulateJourneySuccess(data?.message || `Simulated journey. Duration ${data?.durationMinutes ?? 5} min. Check fare evasion cases.`);
      setTimeout(() => setSimulateJourneySuccess(null), 8000);
      loadGeofenceMonitor();
    } catch (err) {
      setError(err.message);
    } finally {
      setSimulateJourneyLoading(false);
    }
  };

  const handleSendAlertToUser = async (e) => {
    e.preventDefault();
    if (!secret || !alertToUserId.trim() || !alertToMessage.trim()) return;
    setAlertToSuccess(null);
    setAlertToSending(true);
    try {
      await api.sendAdminNotification(secret, alertToUserId.trim(), alertToMessage.trim());
      setAlertToSuccess(`Alert sent to user ${alertToUserId}. They will see it in the app.`);
      setAlertToMessage('');
      setTimeout(() => setAlertToSuccess(null), 4000);
    } catch (e) {
      setError(e.message);
    } finally {
      setAlertToSending(false);
    }
  };

  useEffect(() => {
    if (!secret) return;
    loadUserLocations();
    const interval = setInterval(loadUserLocations, 5000);
    return () => clearInterval(interval);
  }, [secret, loadUserLocations]);

  const handleRecordGeofenceEvent = async (e) => {
    e.preventDefault();
    if (!secret || !simUserId.trim() || !simGeofenceId) return;
    setSimSuccess(null);
    setSimSending(true);
    try {
      await api.recordGeofenceEvent(secret, simUserId.trim(), Number(simGeofenceId), simEventType);
      setSimSuccess('Event recorded. Refresh lists to see it and any new fare evasion.');
      loadGeofenceMonitor();
    } catch (e) {
      setError(e.message);
    } finally {
      setSimSending(false);
    }
  };

  const load = useCallback(async () => {
    if (!secret) {
      setReservations([]);
      setFiltered([]);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await api.getAdminReservations(secret);
      setReservations(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e.message);
      setReservations([]);
    } finally {
      setLoading(false);
    }
  }, [secret]);

  useEffect(() => {
    if (secret) load();
  }, [secret, load]);

  useEffect(() => {
    let items = reservations;
    if (statusFilter !== 'ALL') {
      items = items.filter((r) => r.status === statusFilter);
    }
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      items = items.filter((r) => {
        const trip = `${r.trip?.fromStation || ''} ${r.trip?.toStation || ''}`.toLowerCase();
        const uid = (r.userId || '').toLowerCase();
        const ref = (r.paymentReference || '').toLowerCase();
        return trip.includes(q) || uid.includes(q) || ref.includes(q);
      });
    }
    setFiltered(items);
  }, [reservations, statusFilter, search]);

  const totalByStatus = reservations.reduce(
    (acc, r) => {
      acc[r.status] = (acc[r.status] || 0) + 1;
      return acc;
    },
    {}
  );

  const totalAmount = reservations.reduce((sum, r) => sum + Number(r.amount || 0), 0);

  const handleSendAlert = async (e) => {
    e.preventDefault();
    if (!secret || !geofenceUserId.trim() || !geofenceTripId) return;
    setAlertSuccess(null);
    setAlertSending(true);
    try {
      await api.adminAlertUserWithoutTicket(secret, geofenceUserId.trim(), Number(geofenceTripId));
      setAlertSuccess('Alert sent. The user will see it when they next open the app.');
      setGeofenceUserId('');
    } catch (e) {
      setError(e.message);
    } finally {
      setAlertSending(false);
    }
  };

  const navItem = (id, label, icon) => (
    <button
      type="button"
      onClick={() => setSection(id)}
      className={`w-full flex items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm font-medium transition-colors ${
        section === id
          ? 'bg-indigo-600 text-white'
          : 'text-slate-300 hover:bg-slate-700 hover:text-white'
      }`}
    >
      <span className="text-lg opacity-80">{icon}</span>
      {label}
    </button>
  );

  return (
    <div className="flex min-h-screen bg-slate-100">
      {/* Sidebar */}
      <aside className="w-56 flex-shrink-0 flex flex-col bg-slate-800 border-r border-slate-700">
        <div className="p-4 border-b border-slate-700">
          <h1 className="text-lg font-bold text-white tracking-tight">RailBook</h1>
          <p className="text-xs text-slate-400 mt-0.5">Admin dashboard</p>
        </div>
        <nav className="flex-1 p-3 space-y-1">
          {navItem('overview', 'Overview', '▣')}
          {navItem('reservations', 'Reservations', '📋')}
          {navItem('geofence', 'Geofence monitor', '📍')}
          {navItem('audit', 'Audit log', '📜')}
          {navItem('refunds', 'Refund requests', '↩')}
        </nav>
        <div className="p-3 border-t border-slate-700">
          <button
            type="button"
            onClick={onLogout}
            className="w-full flex items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm font-medium text-slate-300 hover:bg-slate-700 hover:text-white"
          >
            <span className="text-lg">⎋</span>
            Log out
          </button>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto p-6">
        {error && (
          <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">
            {error}
          </div>
        )}

        {section === 'overview' && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-slate-900">Overview</h2>
            <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
              <h3 className="text-sm font-semibold text-slate-800 mb-4">Reservations summary</h3>
              <div className="flex flex-wrap gap-4 text-sm text-slate-700">
                <div>
                  <span className="font-medium">{reservations.length}</span> total reservations
                </div>
                <div>
                  <span className="font-medium">£{totalAmount.toFixed(2)}</span> total amount (all statuses)
                </div>
                <div className="flex flex-wrap gap-2">
                  {['RESERVED', 'PAID', 'CONFIRMED', 'EXPIRED'].map((s) => (
                    <span key={s} className="flex items-center gap-1">
                      {statusBadge(s)}
                      <span className="text-xs text-slate-600">
                        {totalByStatus[s] || 0}
                      </span>
                    </span>
                  ))}
                </div>
              </div>
              <button
                type="button"
                onClick={load}
                disabled={loading}
                className="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
              >
                {loading ? 'Loading…' : 'Refresh data'}
              </button>
            </div>
          </div>
        )}

        {section === 'reservations' && (
          <div className="space-y-6">
            <div className="flex items-center justify-between">
              <h2 className="text-xl font-semibold text-slate-900">Reservations</h2>
              <button
                type="button"
                onClick={load}
                disabled={loading}
                className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
              >
                {loading ? 'Loading…' : 'Refresh'}
              </button>
            </div>
            <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm space-y-4">
        <div className="flex flex-wrap items-center gap-3">
          <div>
            <label className="block text-xs font-medium text-gray-600">Status</label>
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="mt-1 rounded-lg border border-gray-300 bg-white px-2 py-1 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
            >
              <option value="ALL">All</option>
              <option value="RESERVED">Reserved</option>
              <option value="PAID">Paid</option>
              <option value="CONFIRMED">Confirmed</option>
              <option value="EXPIRED">Expired</option>
            </select>
          </div>
          <div className="flex-1 min-w-[200px]">
            <label className="block text-xs font-medium text-gray-600">
              Search (userId, route, payment ref)
            </label>
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-1.5 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
              placeholder="e.g. user-1, London, PAY-"
            />
          </div>
          <p className="text-xs text-gray-500">
            Showing {filtered.length} of {reservations.length} reservations
          </p>
        </div>

        <div className="overflow-x-auto">
          <table className="min-w-full text-left text-xs text-gray-700">
            <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide">
              <tr>
                <th className="px-2 py-2">When</th>
                <th className="px-2 py-2">User</th>
                <th className="px-2 py-2">Route</th>
                <th className="px-2 py-2">Train</th>
                <th className="px-2 py-2">Seats</th>
                <th className="px-2 py-2">Status</th>
                <th className="px-2 py-2">Amount</th>
                <th className="px-2 py-2">Payment ID</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id} className="border-b border-gray-100 hover:bg-gray-50">
                  <td className="px-2 py-1 align-top">
                    <div>{formatDate(r.createdAt)}</div>
                    {r.expiresAt && (
                      <div className="text-[10px] text-gray-500">
                        expires {formatDate(r.expiresAt)}
                      </div>
                    )}
                  </td>
                  <td className="px-2 py-1 align-top text-[11px]">
                    {r.userId}
                  </td>
                  <td className="px-2 py-1 align-top text-[11px]">
                    <div className="font-medium text-gray-900">
                      {r.trip?.fromStation} → {r.trip?.toStation}
                    </div>
                    <div className="text-[10px] text-gray-600">
                      {formatDate(r.trip?.departureTime)}
                    </div>
                  </td>
                  <td className="px-2 py-1 align-top text-[11px]">
                    {r.trip?.trainName}
                  </td>
                  <td className="px-2 py-1 align-top text-[11px]">
                    <div className="flex flex-wrap gap-1">
                      {r.seats?.map((s) => (
                        <span
                          key={s.seatId}
                          className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px]"
                        >
                          {s.seatNumber}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="px-2 py-1 align-top">
                    {statusBadge(r.status)}
                  </td>
                  <td className="px-2 py-1 align-top text-[11px] font-medium text-gray-900">
                    £{Number(r.amount || 0).toFixed(2)}
                  </td>
                  <td className="px-2 py-1 align-top text-[11px]">
                    {r.paymentReference || '—'}
                  </td>
                </tr>
              ))}
              {!filtered.length && (
                <tr>
                  <td
                    colSpan={8}
                    className="px-2 py-6 text-center text-sm text-gray-500"
                  >
                    {loading
                      ? 'Loading reservations…'
                      : 'No reservations match the current filters.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm space-y-3">
        <h3 className="text-sm font-semibold text-gray-800">Geofencing – Alert user without ticket</h3>
        <p className="text-xs text-gray-600">
          Simulate detecting a user on a train without a valid ticket. They will see an in-app alert to purchase a ticket.
        </p>
        <form onSubmit={handleSendAlert} className="flex flex-wrap items-end gap-3">
          <div className="min-w-[140px]">
            <label className="block text-xs font-medium text-gray-600">User ID</label>
            <input
              type="text"
              value={geofenceUserId}
              onChange={(e) => setGeofenceUserId(e.target.value)}
              placeholder="e.g. 1 or user-1"
              className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
              required
            />
          </div>
          <div className="min-w-[220px]">
            <label className="block text-xs font-medium text-gray-600">Trip (train / route)</label>
            <select
              value={geofenceTripId}
              onChange={(e) => setGeofenceTripId(e.target.value)}
              className="mt-1 w-full rounded-lg border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
              required
            >
              <option value="">Select trip</option>
              {trips.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.fromStation} → {t.toStation} ({t.trainName})
                </option>
              ))}
            </select>
          </div>
          <button
            type="submit"
            disabled={alertSending || !secret}
            className="rounded-lg bg-black px-4 py-2 text-sm font-medium text-white hover:bg-gray-800 disabled:opacity-50"
          >
            {alertSending ? 'Sending…' : 'Send alert'}
          </button>
        </form>
        {alertSuccess && (
          <p className="text-sm text-green-700">{alertSuccess}</p>
        )}
            </div>
          </div>
        )}

        {section === 'geofence' && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-slate-900">Geofence monitor</h2>

            {fareEvasionAlert && (
              <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-amber-900 flex items-center justify-between" role="alert">
                <span className="font-medium">{fareEvasionAlert}</span>
                <button type="button" onClick={() => setFareEvasionAlert(null)} className="text-amber-700 hover:text-amber-900">×</button>
              </div>
            )}
            {notificationSent && (
              <div className="rounded-lg border border-green-300 bg-green-50 px-4 py-2 text-green-800 text-sm">
                Notification sent to {notificationSent}.
              </div>
            )}

            <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm space-y-4">
              <h3 className="text-sm font-semibold text-slate-800">Map – all stations</h3>
              <p className="text-xs text-slate-600">
                All station geofences (purple circles) on every line. Click a circle to see station details. Data loads when you open this section; click Refresh to reload.
              </p>
              {geofences.length === 0 && !geofenceMonitorLoading && (
                <p className="text-amber-700 text-sm">No geofences yet. Click &quot;Refresh data&quot; below to load from the backend. If the backend was just started, ensure the DB was reset so geofences are seeded.</p>
              )}
              {geofences.length > 0 && (
                <p className="text-slate-600 text-sm">{geofences.length} station geofence(s) on map.</p>
              )}
              <AdminGeofenceMap geofences={geofences} />
              <button
                type="button"
                onClick={loadGeofenceMonitor}
                disabled={geofenceMonitorLoading || !secret}
                className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
              >
                {geofenceMonitorLoading ? 'Loading…' : 'Refresh data'}
              </button>

              <div className="border-t border-slate-200 pt-4 mt-4">
                <h4 className="text-xs font-semibold text-slate-700 mb-2">Show user without ticket (e.g. user 4)</h4>
                <p className="text-xs text-slate-500 mb-2">Simulate one user travelling Sheffield → Doncaster with no ticket. They will get UNPAID_TRAVEL and a penalty notification.</p>
                <form onSubmit={handleSimulateNoTicket} className="flex flex-wrap items-end gap-3">
                  <div className="min-w-[100px]">
                    <label className="block text-xs font-medium text-gray-600">User ID</label>
                    <input
                      type="text"
                      value={simulateNoTicketUserId}
                      onChange={(e) => setSimulateNoTicketUserId(e.target.value)}
                      placeholder="4"
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
                    />
                  </div>
                  <button
                    type="submit"
                    disabled={simulateNoTicketLoading || !secret}
                    className="rounded-lg bg-amber-600 px-4 py-2 text-sm font-medium text-white hover:bg-amber-500 disabled:opacity-50"
                  >
                    {simulateNoTicketLoading ? 'Running…' : 'Simulate: no ticket Sheffield → Doncaster'}
                  </button>
                </form>
                {simulateNoTicketSuccess && <p className="text-sm text-green-700 mt-2">{simulateNoTicketSuccess}</p>}
              </div>

              <div className="border-t border-slate-200 pt-4 mt-4">
                <h4 className="text-xs font-semibold text-slate-700 mb-2">Simulate journey (any station → any station, real-time, no ticket)</h4>
                <p className="text-xs text-slate-500 mb-2">User enters origin at first time, exits origin, then enters destination (e.g. 19:45 and 19:50 = 5 min journey). Trip segment and PENDING_RESOLUTION created.</p>
                <form onSubmit={handleSimulateJourney} className="flex flex-wrap items-end gap-3">
                  <div className="min-w-[80px]">
                    <label className="block text-xs font-medium text-gray-600">User ID</label>
                    <input
                      type="text"
                      value={simulateJourneyUserId}
                      onChange={(e) => setSimulateJourneyUserId(e.target.value)}
                      placeholder="4"
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
                    />
                  </div>
                  <div className="min-w-[140px]">
                    <label className="block text-xs font-medium text-gray-600">Origin station</label>
                    <select
                      value={simulateJourneyOriginId}
                      onChange={(e) => setSimulateJourneyOriginId(e.target.value)}
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm bg-white"
                    >
                      <option value="">Select origin</option>
                      {geofences.map((g) => (
                        <option key={g.id} value={g.id}>{g.name}</option>
                      ))}
                    </select>
                  </div>
                  <div className="min-w-[140px]">
                    <label className="block text-xs font-medium text-gray-600">Destination station</label>
                    <select
                      value={simulateJourneyDestId}
                      onChange={(e) => setSimulateJourneyDestId(e.target.value)}
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm bg-white"
                    >
                      <option value="">Select destination</option>
                      {geofences.map((g) => (
                        <option key={g.id} value={g.id}>{g.name}</option>
                      ))}
                    </select>
                  </div>
                  <div className="min-w-[80px]">
                    <label className="block text-xs font-medium text-gray-600">Enter origin (HH:mm)</label>
                    <input
                      type="text"
                      value={simulateJourneyEnterOrigin}
                      onChange={(e) => setSimulateJourneyEnterOrigin(e.target.value)}
                      placeholder="19:45"
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
                    />
                  </div>
                  <div className="min-w-[80px]">
                    <label className="block text-xs font-medium text-gray-600">Enter destination (HH:mm)</label>
                    <input
                      type="text"
                      value={simulateJourneyEnterDest}
                      onChange={(e) => setSimulateJourneyEnterDest(e.target.value)}
                      placeholder="19:50"
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
                    />
                  </div>
                  <button
                    type="submit"
                    disabled={simulateJourneyLoading || !secret || !simulateJourneyOriginId || !simulateJourneyDestId}
                    className="rounded-lg bg-teal-600 px-4 py-2 text-sm font-medium text-white hover:bg-teal-500 disabled:opacity-50"
                  >
                    {simulateJourneyLoading ? 'Running…' : 'Simulate journey'}
                  </button>
                </form>
                {simulateJourneySuccess && <p className="text-sm text-green-700 mt-2">{simulateJourneySuccess}</p>}
              </div>

              <div className="border-t border-slate-200 pt-4 mt-4">
                <h4 className="text-xs font-semibold text-slate-700 mb-2">Send alert to user</h4>
                <p className="text-xs text-slate-500 mb-2">Send a message to a user (e.g. 4). They will see it in the app under the bell icon.</p>
                <form onSubmit={handleSendAlertToUser} className="flex flex-wrap items-end gap-3">
                  <div className="min-w-[100px]">
                    <label className="block text-xs font-medium text-gray-600">User ID</label>
                    <input
                      type="text"
                      value={alertToUserId}
                      onChange={(e) => setAlertToUserId(e.target.value)}
                      placeholder="4"
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
                      required
                    />
                  </div>
                  <div className="flex-1 min-w-[200px]">
                    <label className="block text-xs font-medium text-gray-600">Message</label>
                    <input
                      type="text"
                      value={alertToMessage}
                      onChange={(e) => setAlertToMessage(e.target.value)}
                      placeholder="e.g. Please purchase a ticket or pay the penalty."
                      className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
                      required
                    />
                  </div>
                  <button
                    type="submit"
                    disabled={alertToSending || !secret}
                    className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
                  >
                    {alertToSending ? 'Sending…' : 'Send alert'}
                  </button>
                </form>
                {alertToSuccess && <p className="text-sm text-green-700 mt-2">{alertToSuccess}</p>}
              </div>
            </div>

      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm space-y-4">
        <h3 className="text-sm font-semibold text-slate-800">Stations, events & fare evasion</h3>
        <p className="text-xs text-slate-600">
          User locations are reported automatically from the app (every 15s). When a user enters or leaves a station geofence, alerts and fare evasion are recorded. List below updates every 5 seconds.
        </p>

        <div>
          <h4 className="text-xs font-semibold text-gray-700 mb-1">Live user locations (auto-updated every 5s)</h4>
          <div className="overflow-x-auto max-h-48 overflow-y-auto">
            <table className="min-w-full text-left text-xs text-gray-700">
              <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide sticky top-0">
                <tr>
                  <th className="px-2 py-1">User ID</th>
                  <th className="px-2 py-1">Latitude</th>
                  <th className="px-2 py-1">Longitude</th>
                  <th className="px-2 py-1">Last updated</th>
                </tr>
              </thead>
              <tbody>
                {userLocations.map((loc) => (
                  <tr key={loc.userId} className="border-b border-gray-100">
                    <td className="px-2 py-1 font-medium">{loc.userId}</td>
                    <td className="px-2 py-1">{loc.latitude?.toFixed(5)}</td>
                    <td className="px-2 py-1">{loc.longitude?.toFixed(5)}</td>
                    <td className="px-2 py-1 whitespace-nowrap">{formatDate(loc.updatedAt)}</td>
                  </tr>
                ))}
                {userLocations.length === 0 && !geofenceMonitorLoading && (
                  <tr>
                    <td colSpan={4} className="px-2 py-3 text-gray-500">
                      No user locations yet. Users must be logged in and have allowed location access; their app reports position every 15s.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <h4 className="text-xs font-semibold text-gray-700 mb-1">Geofences</h4>
          <div className="overflow-x-auto max-h-32 overflow-y-auto">
            <table className="min-w-full text-left text-xs text-gray-700">
              <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide">
                <tr>
                  <th className="px-2 py-1">Name</th>
                  <th className="px-2 py-1">Station</th>
                  <th className="px-2 py-1">Lat / Long</th>
                  <th className="px-2 py-1">Radius (m)</th>
                </tr>
              </thead>
              <tbody>
                {geofences.map((g) => (
                  <tr key={g.id} className="border-b border-gray-100">
                    <td className="px-2 py-1">{g.name}</td>
                    <td className="px-2 py-1">{g.stationName}</td>
                    <td className="px-2 py-1">{g.latitude?.toFixed(4)}, {g.longitude?.toFixed(4)}</td>
                    <td className="px-2 py-1">{g.radiusMeters}</td>
                  </tr>
                ))}
                {geofences.length === 0 && !geofenceMonitorLoading && (
                  <tr><td colSpan={4} className="px-2 py-2 text-gray-500">Click Refresh to load geofences.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <h4 className="text-xs font-semibold text-gray-700 mb-1">Recent geofence events</h4>
          <div className="overflow-x-auto max-h-40 overflow-y-auto">
            <table className="min-w-full text-left text-xs text-gray-700">
              <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide sticky top-0">
                <tr>
                  <th className="px-2 py-1">Time</th>
                  <th className="px-2 py-1">User</th>
                  <th className="px-2 py-1">Geofence</th>
                  <th className="px-2 py-1">Event</th>
                </tr>
              </thead>
              <tbody>
                {geofenceEvents.map((ev) => (
                  <tr key={ev.id} className="border-b border-gray-100">
                    <td className="px-2 py-1 whitespace-nowrap">{formatDate(ev.createdAt)}</td>
                    <td className="px-2 py-1">{ev.userId}</td>
                    <td className="px-2 py-1">{ev.geofence?.name ?? '—'}</td>
                    <td className="px-2 py-1">
                      <span className={ev.eventType === 'ENTERED' ? 'text-green-700 font-medium' : 'text-amber-700 font-medium'}>
                        {ev.eventType}
                      </span>
                    </td>
                  </tr>
                ))}
                {geofenceEvents.length === 0 && !geofenceMonitorLoading && (
                  <tr><td colSpan={4} className="px-2 py-2 text-gray-500">No events yet. Simulate below.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <h4 className="text-xs font-semibold text-gray-700 mb-1">Fare evasion audit (no valid ticket)</h4>
          <div className="overflow-x-auto max-h-40 overflow-y-auto">
            <table className="min-w-full text-left text-xs text-gray-700">
              <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide sticky top-0">
                <tr>
                  <th className="px-2 py-1">Time</th>
                  <th className="px-2 py-1">User</th>
                  <th className="px-2 py-1">Details</th>
                </tr>
              </thead>
              <tbody>
                {fareEvasionLogs.map((a) => (
                  <tr key={a.id} className="border-b border-gray-100">
                    <td className="px-2 py-1 whitespace-nowrap">{formatDate(a.createdAt)}</td>
                    <td className="px-2 py-1">{a.userId}</td>
                    <td className="px-2 py-1 text-gray-600">{a.details || '—'}</td>
                  </tr>
                ))}
                {fareEvasionLogs.length === 0 && !geofenceMonitorLoading && (
                  <tr><td colSpan={3} className="px-2 py-2 text-gray-500">No fare evasion recorded yet.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <h4 className="text-xs font-semibold text-gray-700 mb-1">Fare evasion cases (pending resolution / penalty)</h4>
          <p className="text-xs text-slate-500 mb-1">User ID, Origin, Destination, Departure, Arrival, Platform, Ticket: NONE, Status. Admin can monitor and confirm penalty or cancel case.</p>
          <div className="overflow-x-auto max-h-48 overflow-y-auto">
            <table className="min-w-full text-left text-xs text-gray-700">
              <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide sticky top-0">
                <tr>
                  <th className="px-2 py-1">User ID</th>
                  <th className="px-2 py-1">Origin</th>
                  <th className="px-2 py-1">Destination</th>
                  <th className="px-2 py-1">Departure</th>
                  <th className="px-2 py-1">Arrival</th>
                  <th className="px-2 py-1">Platform</th>
                  <th className="px-2 py-1">Ticket</th>
                  <th className="px-2 py-1">Status</th>
                </tr>
              </thead>
              <tbody>
                {fareEvasionCases.map((c) => (
                  <tr key={c.id} className="border-b border-gray-100">
                    <td className="px-2 py-1 font-medium">{c.passengerId}</td>
                    <td className="px-2 py-1">{c.originStation}</td>
                    <td className="px-2 py-1">{c.destinationStation}</td>
                    <td className="px-2 py-1 whitespace-nowrap">{formatDate(c.segmentStartTime)}</td>
                    <td className="px-2 py-1 whitespace-nowrap">{formatDate(c.segmentEndTime)}</td>
                    <td className="px-2 py-1">{(c.originPlatform || '—') + ' → ' + (c.destinationPlatform || '—')}</td>
                    <td className="px-2 py-1">NONE</td>
                    <td className="px-2 py-1">
                      <span className={c.fareStatus === 'PENDING_RESOLUTION' ? 'text-orange-700 font-medium' : 'text-red-700 font-medium'}>
                        {c.fareStatus === 'PENDING_RESOLUTION' ? 'Pending Resolution' : c.fareStatus === 'UNPAID_TRAVEL' ? 'Penalty issued' : c.fareStatus}
                      </span>
                    </td>
                  </tr>
                ))}
                {fareEvasionCases.length === 0 && !geofenceMonitorLoading && (
                  <tr><td colSpan={8} className="px-2 py-2 text-gray-500">No fare evasion cases.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div>
          <h4 className="text-xs font-semibold text-gray-700 mb-1">Trip segments (fare validation)</h4>
          <p className="text-xs text-slate-500 mb-1">Created when StationExitDetected(origin) then StationEntryDetected(destination). PAID = valid ticket; PENDING_RESOLUTION = 1h to resolve; UNPAID_TRAVEL = penalty.</p>
          <div className="overflow-x-auto max-h-40 overflow-y-auto">
            <table className="min-w-full text-left text-xs text-gray-700">
              <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide sticky top-0">
                <tr>
                  <th className="px-2 py-1">Passenger</th>
                  <th className="px-2 py-1">Origin → Dest</th>
                  <th className="px-2 py-1">Start / End</th>
                  <th className="px-2 py-1">Fare status</th>
                  <th className="px-2 py-1">Paid / Add’l / Penalty</th>
                </tr>
              </thead>
              <tbody>
                {tripSegments.map((s) => (
                  <tr key={s.id} className="border-b border-gray-100">
                    <td className="px-2 py-1 font-medium">{s.passengerId}</td>
                    <td className="px-2 py-1">{s.originStation} → {s.destinationStation}</td>
                    <td className="px-2 py-1 whitespace-nowrap">{formatDate(s.segmentStartTime)} / {formatDate(s.segmentEndTime)}</td>
                    <td className="px-2 py-1">
                      <span className={s.fareStatus === 'PAID' ? 'text-green-700 font-medium' : s.fareStatus === 'PENDING_RESOLUTION' ? 'text-orange-700 font-medium' : s.fareStatus === 'UNDERPAID' ? 'text-amber-700 font-medium' : 'text-red-700 font-medium'}>
                        {s.fareStatus}
                      </span>
                    </td>
                    <td className="px-2 py-1">£{Number(s.paidFare || 0).toFixed(2)} / £{Number(s.additionalFare || 0).toFixed(2)} / £{Number(s.penaltyAmount || 0).toFixed(2)}</td>
                  </tr>
                ))}
                {tripSegments.length === 0 && !geofenceMonitorLoading && (
                  <tr><td colSpan={5} className="px-2 py-2 text-gray-500">No trip segments yet. Segments are created when real users exit one station geofence and enter another (from app location).</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div className="border-t border-gray-200 pt-3">
          <h4 className="text-xs font-semibold text-gray-700 mb-2">Simulate geofence event</h4>
          <form onSubmit={handleRecordGeofenceEvent} className="flex flex-wrap items-end gap-3">
            <div className="min-w-[120px]">
              <label className="block text-xs font-medium text-gray-600">User ID</label>
              <input
                type="text"
                value={simUserId}
                onChange={(e) => setSimUserId(e.target.value)}
                placeholder="e.g. 1"
                className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
                required
              />
            </div>
            <div className="min-w-[180px]">
              <label className="block text-xs font-medium text-gray-600">Geofence</label>
              <select
                value={simGeofenceId}
                onChange={(e) => setSimGeofenceId(e.target.value)}
                className="mt-1 w-full rounded-lg border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
                required
              >
                <option value="">Select station</option>
                {geofences.map((g) => (
                  <option key={g.id} value={g.id}>{g.name}</option>
                ))}
              </select>
            </div>
            <div className="min-w-[120px]">
              <label className="block text-xs font-medium text-gray-600">Event</label>
              <select
                value={simEventType}
                onChange={(e) => setSimEventType(e.target.value)}
                className="mt-1 w-full rounded-lg border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
              >
                <option value="ENTERED">ENTERED</option>
                <option value="EXITED">EXITED</option>
              </select>
            </div>
            <button
              type="submit"
              disabled={simSending || !secret}
              className="rounded-lg bg-black px-4 py-2 text-sm font-medium text-white hover:bg-gray-800 disabled:opacity-50"
            >
              {simSending ? 'Recording…' : 'Record event'}
            </button>
          </form>
          {simSuccess && <p className="text-sm text-green-700 mt-2">{simSuccess}</p>}
        </div>
      </div>
          </div>
        )}

        {section === 'audit' && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-slate-900">Audit log</h2>
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm space-y-3">
        <h3 className="text-sm font-semibold text-slate-800">Track user movements</h3>
        <p className="text-xs text-gray-600">
          View every action by users (and admin). Filter by User ID to track a specific user.
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[140px]">
            <label className="block text-xs font-medium text-gray-600">User ID (optional)</label>
            <input
              type="text"
              value={auditUserId}
              onChange={(e) => setAuditUserId(e.target.value)}
              placeholder="e.g. 1 = all"
              className="mt-1 w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm text-gray-900 focus:border-black focus:outline-none focus:ring-1 focus:ring-black"
            />
          </div>
          <button
            type="button"
            onClick={loadAuditLogs}
            disabled={auditLoading || !secret}
            className="rounded-lg bg-black px-4 py-2 text-sm font-medium text-white hover:bg-gray-800 disabled:opacity-50"
          >
            {auditLoading ? 'Loading…' : 'Load audit log'}
          </button>
        </div>
        <div className="overflow-x-auto max-h-64 overflow-y-auto">
          <table className="min-w-full text-left text-xs text-gray-700">
            <thead className="border-b border-gray-200 bg-gray-50 text-[11px] uppercase tracking-wide sticky top-0">
              <tr>
                <th className="px-2 py-1.5">Time</th>
                <th className="px-2 py-1.5">User</th>
                <th className="px-2 py-1.5">Action</th>
                <th className="px-2 py-1.5">Details</th>
              </tr>
            </thead>
            <tbody>
              {auditLogs.map((a) => (
                <tr key={a.id} className="border-b border-gray-100">
                  <td className="px-2 py-1 whitespace-nowrap">{formatDate(a.createdAt)}</td>
                  <td className="px-2 py-1">{a.userId || '—'}</td>
                  <td className="px-2 py-1 font-medium">{a.action}</td>
                  <td className="px-2 py-1 text-gray-600">{a.details || '—'}</td>
                </tr>
              ))}
              {auditLogs.length === 0 && !auditLoading && (
                <tr>
                  <td colSpan={4} className="px-2 py-4 text-center text-gray-500">Load audit log to see entries.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
          </div>
        )}

        {section === 'refunds' && (
          <div className="space-y-6">
            <h2 className="text-xl font-semibold text-slate-900">Refund requests</h2>
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm space-y-3">
        <h3 className="text-sm font-semibold text-slate-800">Manage user refunds</h3>
        <p className="text-xs text-gray-600">
          Users can request a refund within 3 minutes of payment. Approve or reject below.
        </p>
        <button
          type="button"
          onClick={loadRefundRequests}
          disabled={refundLoading || !secret}
          className="rounded-lg bg-black px-4 py-2 text-sm font-medium text-white hover:bg-gray-800 disabled:opacity-50"
        >
          {refundLoading ? 'Loading…' : 'Refresh refund requests'}
        </button>
        <div className="space-y-2">
          {refundRequests.length === 0 && !refundLoading && (
            <p className="text-sm text-gray-500">No pending refund requests.</p>
          )}
          {refundRequests.map((req) => (
            <div key={req.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-gray-200 bg-gray-50 p-3 text-sm">
              <div>
                <span className="font-medium">User {req.userId}</span>
                <span className="text-gray-600"> · Reservation #{req.reservationId}</span>
                {req.reservation?.trip && (
                  <span className="text-gray-600"> · {req.reservation.trip.fromStation} → {req.reservation.trip.toStation}</span>
                )}
                <span className="text-gray-500 text-xs block">Requested {formatDate(req.requestedAt)}</span>
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={async () => {
                    setRefundActionId(req.id);
                    try {
                      await api.adminApproveRefund(secret, req.id);
                      loadRefundRequests();
                    } catch (e) {
                      setError(e.message);
                    } finally {
                      setRefundActionId(null);
                    }
                  }}
                  disabled={refundActionId !== null}
                  className="rounded bg-green-600 px-2 py-1 text-xs font-medium text-white hover:bg-green-700 disabled:opacity-50"
                >
                  {refundActionId === req.id ? '…' : 'Approve'}
                </button>
                <button
                  type="button"
                  onClick={async () => {
                    setRefundActionId(req.id);
                    try {
                      await api.adminRejectRefund(secret, req.id);
                      loadRefundRequests();
                    } catch (e) {
                      setError(e.message);
                    } finally {
                      setRefundActionId(null);
                    }
                  }}
                  disabled={refundActionId !== null}
                  className="rounded bg-red-600 px-2 py-1 text-xs font-medium text-white hover:bg-red-700 disabled:opacity-50"
                >
                  Reject
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
          </div>
        )}
      </main>
    </div>
  );
}

export default AdminDashboard;


import React, { useState, useCallback, useEffect } from 'react';
import * as api from './api/client';
import TripList from './components/TripList';
import SeatSelect from './components/SeatSelect';
import Payment from './components/Payment';
import Confirmation from './components/Confirmation';
import Login from './components/Login';
import Register from './components/Register';
import StationEntryModal from './components/StationEntryModal';
import Landing from './components/Landing';
import TabBar from './components/TabBar';
import HomeScreen from './screens/HomeScreen';
import JourneyScreen from './screens/JourneyScreen';
import TicketsScreen from './screens/TicketsScreen';
import ProfileScreen from './screens/ProfileScreen';
const STEPS = { TRIPS: 'trips', SEATS: 'seats', PAYMENT: 'payment', CONFIRM: 'confirm' };
const VIEWS = { LANDING: 'landing', LOGIN: 'login', REGISTER: 'register' };
const TABS = { HOME: 'home', JOURNEY: 'journey', TICKETS: 'tickets', PROFILE: 'profile' };

function App() {
  const [user, setUser] = useState(api.getStoredUser());
  const [view, setView] = useState(user ? null : VIEWS.LANDING);
  const [activeTab, setActiveTab] = useState(TABS.HOME);
  const [showBookingFlow, setShowBookingFlow] = useState(false);
  const [step, setStep] = useState(STEPS.TRIPS);
  const [trips, setTrips] = useState([]);
  const [selectedTrip, setSelectedTrip] = useState(null);
  const [seats, setSeats] = useState([]);
  const [reservations, setReservations] = useState([]);
  const [confirmed, setConfirmed] = useState([]);
  const [bookings, setBookings] = useState([]);
  const [bookingsLoading, setBookingsLoading] = useState(false);
  const [segments, setSegments] = useState([]);
  const [currentStation, setCurrentStation] = useState(null);
  const [departures, setDepartures] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [showNotifications, setShowNotifications] = useState(false);
  const [pendingStationEntryActions, setPendingStationEntryActions] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [fromStation, setFromStation] = useState('Leeds');
  const [toStation, setToStation] = useState('Sheffield');

  const loadTrips = useCallback(async (from, to) => {
    setError(null);
    setLoading(true);
    try {
      let data = await api.getTrips(from, to);
      data = Array.isArray(data) ? data : [];
      // Fallback: if filtered request returns nothing, try unfiltered and filter client-side
      if (data.length === 0 && from && to) {
        const all = await api.getTrips();
        const list = Array.isArray(all) ? all : [];
        data = list.filter((t) => t.fromStation === from && t.toStation === to);
      }
      setTrips(data);
      setStep(STEPS.TRIPS);
    } catch (e) {
      setError(e.message);
      setTrips([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadBookings = useCallback(async () => {
    setBookingsLoading(true);
    setError(null);
    try {
      const data = await api.getMyBookings();
      setBookings(Array.isArray(data) ? data : []);
    } catch (e) {
      setError(e.message);
    } finally {
      setBookingsLoading(false);
    }
  }, []);

  const loadSegments = useCallback(async () => {
    if (!user?.userId) return;
    try {
      const data = await api.getMyTripSegments(50);
      setSegments(Array.isArray(data) ? data : []);
    } catch {
      setSegments([]);
    }
  }, [user?.userId]);

  const loadCurrentStationAndDepartures = useCallback(async () => {
    if (!user?.userId) return;
    try {
      const station = await api.getCurrentStation();
      setCurrentStation(station || null);
      if (station?.stationName) {
        const list = await api.getStationDepartures(station.stationName);
        setDepartures(Array.isArray(list) ? list : []);
      } else {
        setDepartures([]);
      }
    } catch {
      setCurrentStation(null);
      setDepartures([]);
    }
  }, [user?.userId]);

  const handleEnableLocation = useCallback(() => {
    if (!user?.userId || !navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        api.reportLocation(pos.coords.latitude, pos.coords.longitude)
          .then(() => loadCurrentStationAndDepartures())
          .catch(() => loadCurrentStationAndDepartures());
      },
      () => {},
      { enableHighAccuracy: false, timeout: 10000, maximumAge: 0 }
    );
  }, [user?.userId, loadCurrentStationAndDepartures]);

  const handleSelectTrip = useCallback(async (trip) => {
    setSelectedTrip(trip);
    setError(null);
    setLoading(true);
    try {
      const data = await api.getSeats(trip.id);
      setSeats(data);
      setStep(STEPS.SEATS);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  const handleRetrySeats = useCallback(async () => {
    if (!selectedTrip) return;
    setError(null);
    setLoading(true);
    try {
      const data = await api.getSeats(selectedTrip.id);
      setSeats(data);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [selectedTrip]);

  const handleReserve = useCallback(async (seatIds) => {
    if (!selectedTrip) return;
    setError(null);
    setLoading(true);
    try {
      const data = await api.reserve(selectedTrip.id, seatIds);
      setReservations(Array.isArray(data) ? data : [data]);
      setStep(STEPS.PAYMENT);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [selectedTrip]);

  const handlePayment = useCallback(async (paymentRef) => {
    setError(null);
    setLoading(true);
    try {
      const results = await Promise.all(
        reservations.map((r) => api.payment(r.id, paymentRef))
      );
      setReservations(results);
      setStep(STEPS.CONFIRM);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [reservations]);

  const handleConfirm = useCallback(async () => {
    setError(null);
    setLoading(true);
    try {
      const results = await Promise.all(
        reservations.map((r) => api.confirm(r.id))
      );
      setConfirmed(results);
      setReservations([]);
      setShowBookingFlow(false);
      loadBookings();
      loadSegments();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }, [reservations, loadBookings, loadSegments]);

  const handleStartOver = useCallback(() => {
    setStep(STEPS.TRIPS);
    setSelectedTrip(null);
    setSeats([]);
    setReservations([]);
    setConfirmed([]);
    setError(null);
    setShowBookingFlow(false);
    loadBookings();
    loadSegments();
    loadTrips();
  }, [loadTrips, loadBookings, loadSegments]);

  /** Release reserved seats so they become available for others (Back from payment or Cancel). */
  const releaseReservations = useCallback(async () => {
    if (!reservations?.length) return;
    await Promise.allSettled(
      reservations.filter((r) => r.status === 'RESERVED').map((r) => api.cancelReservation(r.id))
    );
    setReservations([]);
  }, [reservations]);

  const handleBackFromPayment = useCallback(async () => {
    await releaseReservations();
    setStep(STEPS.SEATS);
  }, [releaseReservations]);

  const handleCancelReservation = useCallback(async () => {
    await releaseReservations();
    handleStartOver();
  }, [releaseReservations, handleStartOver]);

  /** When reservation timer hits 0, seat is available for others; go back to seat map. */
  const handleExpired = useCallback(() => {
    setReservations([]);
    setStep(STEPS.SEATS);
  }, []);

  /** After gateway webhook confirms payment, poll found CONFIRMED; move to confirmation step. */
  const handleGatewaySuccess = useCallback(async () => {
    try {
      const list = await api.getMyBookings();
      const ids = (reservations || []).map((r) => r.id);
      const nowConfirmed = (list || []).filter((b) => ids.includes(b.id) && (b.status === 'PAID' || b.status === 'CONFIRMED'));
      if (nowConfirmed.length) {
        setConfirmed(nowConfirmed);
        setReservations([]);
        setStep(STEPS.CONFIRM);
      }
    } catch (_) {}
  }, [reservations]);

  const handleLoginSuccess = useCallback((loggedInUser) => {
    setUser(loggedInUser);
    setView(null);
    setActiveTab(TABS.HOME);
    setError(null);
  }, []);

  const handleLogout = useCallback(() => {
    api.setStoredUser(null);
    setUser(null);
    setView(VIEWS.LANDING);
    setError(null);
  }, []);

  const loadAlerts = useCallback(async () => {
    if (!user?.userId) return;
    try {
      const data = await api.getMyAlerts();
      setAlerts(Array.isArray(data) ? data : []);
    } catch {
      setAlerts([]);
    }
  }, [user?.userId]);

  const loadNotifications = useCallback(async () => {
    if (!user?.userId) return;
    try {
      const data = await api.getMyNotifications();
      setNotifications(Array.isArray(data) ? data : []);
    } catch {
      setNotifications([]);
    }
  }, [user?.userId]);

  const loadPendingStationEntryActions = useCallback(async () => {
    if (!user?.userId) return;
    try {
      const data = await api.getPendingStationEntryActions();
      setPendingStationEntryActions(Array.isArray(data) ? data : []);
    } catch {
      setPendingStationEntryActions([]);
    }
  }, [user?.userId]);

  useEffect(() => {
    if (user) loadBookings();
  }, [user, loadBookings]);

  useEffect(() => {
    if (user) loadSegments();
  }, [user, loadSegments]);

  useEffect(() => {
    if (user) loadAlerts();
  }, [user, loadAlerts]);

  useEffect(() => {
    if (user) loadNotifications();
  }, [user, loadNotifications]);

  useEffect(() => {
    if (user) loadPendingStationEntryActions();
  }, [user, loadPendingStationEntryActions]);

  // Report user location periodically so admin can see live positions and geofence entry/exit is detected
  useEffect(() => {
    if (!user?.userId || !navigator.geolocation) return;
    const REPORT_INTERVAL_MS = 15000;
    const report = () => {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          api.reportLocation(pos.coords.latitude, pos.coords.longitude).catch(() => {});
        },
        () => {},
        { enableHighAccuracy: false, timeout: 5000, maximumAge: 60000 }
      );
    };
    report();
    const id = setInterval(report, REPORT_INTERVAL_MS);
    return () => clearInterval(id);
  }, [user?.userId]);

  const handleDismissAlert = useCallback(async (alertId) => {
    try {
      await api.markAlertRead(alertId);
      await loadAlerts();
    } catch {
      loadAlerts();
    }
  }, [loadAlerts]);

  const handleMarkNotificationRead = useCallback(async (id) => {
    try {
      await api.markNotificationRead(id);
      loadNotifications();
    } catch {
      loadNotifications();
    }
  }, [loadNotifications]);

  const handleCloseStationEntryModal = useCallback(() => {
    setPendingStationEntryActions((prev) => prev.slice(1));
  }, []);

  const handleRefreshStationEntry = useCallback(() => {
    loadPendingStationEntryActions();
  }, [loadPendingStationEntryActions]);

  const handleFromToChange = useCallback((from, to) => {
    setFromStation(from);
    setToStation(to);
    setError(null);
    setLoading(true);
    api.getTrips(from, to).then((data) => {
      setTrips(Array.isArray(data) ? data : []);
    }).catch((e) => setError(e.message)).finally(() => setLoading(false));
  }, []);

  // Not logged in: show landing, login, or register
  if (!user) {
    if (view === VIEWS.LANDING) {
      return (
        <Landing
          onGoToLogin={() => setView(VIEWS.LOGIN)}
          onGoToRegister={() => setView(VIEWS.REGISTER)}
        />
      );
    }
    return (
      <div className="min-h-screen bg-gray-100 text-gray-900 font-sans">
        <header className="border-b border-gray-200 bg-white shadow-sm">
          <div className="mx-auto max-w-4xl px-4 py-4 flex items-center justify-between">
            <button
              type="button"
              onClick={() => setView(VIEWS.LANDING)}
              className="text-xl font-bold tracking-tight text-black hover:opacity-80"
            >
              RailBook
            </button>
            <span className="text-sm text-gray-500">
              {view === VIEWS.LOGIN ? 'Log in' : 'Sign up'}
            </span>
          </div>
        </header>
        <main className="mx-auto max-w-4xl px-4 py-8">
          {view === VIEWS.LOGIN && (
            <Login
              onSuccess={handleLoginSuccess}
              onSwitchToRegister={() => setView(VIEWS.REGISTER)}
            />
          )}
          {view === VIEWS.REGISTER && (
            <Register
              onSuccess={handleLoginSuccess}
              onSwitchToLogin={() => setView(VIEWS.LOGIN)}
            />
          )}
        </main>
      </div>
    );
  }

  const firstPendingAction = pendingStationEntryActions[0] || null;
  const openBooking = () => {
    setFromStation('Leeds');
    setToStation('Sheffield');
    setShowBookingFlow(true);
    setStep(STEPS.TRIPS);
    loadTrips('Leeds', 'Sheffield');
  };

  return (
    <div className="min-h-screen min-w-0 bg-slate-100 text-slate-900 font-sans overflow-x-hidden">
      {firstPendingAction && (
        <StationEntryModal
          action={firstPendingAction}
          onClose={handleCloseStationEntryModal}
          onRefresh={handleRefreshStationEntry}
          onNavigateToBooking={() => { openBooking(); handleCloseStationEntryModal(); }}
        />
      )}

      {showBookingFlow ? (
        <div className="min-h-screen bg-white">
          {step !== STEPS.TRIPS && (
            <header className="border-b border-slate-200 bg-white px-4 py-3 flex items-center justify-between">
              <button type="button" onClick={() => { setShowBookingFlow(false); releaseReservations(); setStep(STEPS.TRIPS); setSelectedTrip(null); setSeats([]); setReservations([]); loadBookings(); loadSegments(); }} className="text-slate-600 hover:text-slate-900">Cancel</button>
              <h1 className="text-lg font-semibold text-slate-900">Buy ticket</h1>
              <span className="w-14" />
            </header>
          )}
          <main className="mx-auto max-w-4xl min-w-0 px-4 py-6">
            {error && (
              <div className="mb-4 rounded-lg border border-red-300 bg-red-50 px-4 py-3 text-red-700 text-sm" role="alert">{error}</div>
            )}
            {step === STEPS.TRIPS && (
              <TripList
                trips={trips}
                loading={loading}
                onSelectTrip={handleSelectTrip}
                onCancel={() => { setShowBookingFlow(false); releaseReservations(); setStep(STEPS.TRIPS); setSelectedTrip(null); setSeats([]); setReservations([]); loadBookings(); loadSegments(); }}
                fromStation={fromStation}
                toStation={toStation}
                onFromToChange={handleFromToChange}
              />
            )}
            {step === STEPS.SEATS && (
              <SeatSelect trip={selectedTrip} seats={seats} loading={loading} onReserve={handleReserve} onBack={() => setStep(STEPS.TRIPS)} onRetry={handleRetrySeats} />
            )}
            {step === STEPS.PAYMENT && (
              <Payment reservations={reservations} loading={loading} onPayment={handlePayment} onBack={handleBackFromPayment} onGatewaySuccess={handleGatewaySuccess} onCancelReservation={handleCancelReservation} onExpired={handleExpired} />
            )}
            {step === STEPS.CONFIRM && (
              <Confirmation reservations={reservations} confirmed={confirmed} loading={loading} onConfirm={handleConfirm} onStartOver={handleStartOver} />
            )}
          </main>
        </div>
      ) : (
        <>
          <header className="sticky top-0 z-30 border-b border-slate-200 bg-white shadow-sm">
            <div className="mx-auto max-w-4xl px-4 py-3 flex items-center justify-between">
              <h1 className="text-lg font-bold text-slate-900">RailBook</h1>
              <div className="flex items-center gap-2">
                <div className="relative">
                  <button type="button" onClick={() => setShowNotifications((s) => !s)} className="relative p-2 text-slate-600 hover:text-slate-900 rounded-full" aria-label="Notifications">
                    <span className="text-xl">🔔</span>
                    {notifications.filter((n) => !n.readAt).length > 0 && (
                      <span className="absolute top-0.5 right-0.5 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-[10px] font-bold text-white">{notifications.filter((n) => !n.readAt).length}</span>
                    )}
                  </button>
                  {showNotifications && (
                    <>
                      <div className="fixed inset-0 z-10" aria-hidden onClick={() => setShowNotifications(false)} />
                      <div className="absolute right-0 top-full mt-1 z-20 w-72 rounded-xl border border-slate-200 bg-white shadow-lg py-2 max-h-64 overflow-y-auto">
                        <p className="px-3 py-2 text-xs font-semibold text-slate-500 uppercase">Notifications</p>
                        {notifications.length === 0 ? <p className="px-3 py-4 text-sm text-slate-500">No messages.</p> : notifications.map((n) => (
                          <div key={n.id} className={`px-3 py-2 border-b border-slate-100 last:border-0 ${!n.readAt ? 'bg-blue-50/50' : ''}`}>
                            <p className="text-sm text-slate-900">{n.message}</p>
                            <p className="text-xs text-slate-500 mt-1">{new Date(n.createdAt).toLocaleString()}</p>
                            {!n.readAt && <button type="button" onClick={() => handleMarkNotificationRead(n.id)} className="mt-1 text-xs text-blue-600 hover:underline">Mark as read</button>}
                          </div>
                        ))}
                      </div>
                    </>
                  )}
                </div>
                <button type="button" onClick={handleLogout} className="text-sm text-slate-600 hover:text-slate-900 font-medium">Log out</button>
              </div>
            </div>
          </header>

          <main className="mx-auto max-w-4xl min-w-0 px-4 py-6">
            {error && (
              <div className="mb-4 rounded-lg border border-red-300 bg-red-50 px-4 py-3 text-red-700 text-sm" role="alert">{error}</div>
            )}
            {activeTab === TABS.HOME && (
              <HomeScreen user={user} alerts={alerts} pendingStationEntryActions={pendingStationEntryActions} bookings={bookings} segments={segments} currentStation={currentStation} departures={departures} onBuyTicket={openBooking} onViewJourney={() => setActiveTab(TABS.JOURNEY)} onDismissAlert={handleDismissAlert} onRefreshStation={loadCurrentStationAndDepartures} onEnableLocation={handleEnableLocation} />
            )}
            {activeTab === TABS.JOURNEY && <JourneyScreen segments={segments} onBuyTicket={openBooking} onRefreshSegments={loadSegments} />}
            {activeTab === TABS.TICKETS && <TicketsScreen bookings={bookings} bookingsLoading={bookingsLoading} onBuyTicket={openBooking} />}
            {activeTab === TABS.PROFILE && <ProfileScreen user={user} onLogout={handleLogout} />}
          </main>

          <TabBar activeTab={activeTab} onSelect={setActiveTab} />
        </>
      )}
    </div>
  );
}

export default App;

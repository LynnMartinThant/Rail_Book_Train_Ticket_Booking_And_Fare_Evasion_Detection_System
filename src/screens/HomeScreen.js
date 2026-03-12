import React from 'react';
import TicketStatusBadge from '../components/TicketStatusBadge';
import NoTicketBanner from '../components/NoTicketBanner';

const PLATFORM_ORDER = ['1A', '1B', '2A', '2B', '3A', '3B', '4A', '4B', '5A', '5B'];

function formatDate(iso) {
  if (!iso) return '-';
  return new Date(iso).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function formatTime(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
}

function HomeScreen(props) {
  const { user, alerts, pendingStationEntryActions, bookings, segments, currentStation, departures, onBuyTicket, onViewJourney, onRefreshStation, onEnableLocation } = props;
  const unreadAlerts = alerts ? alerts.filter(function(a) { return !a.readAt; }) : [];
  const hasNoTicketWarning = unreadAlerts.length > 0 || (pendingStationEntryActions && pendingStationEntryActions.length > 0);
  const latestAlert = unreadAlerts[0] || (alerts && alerts[0]);
  const recentBookings = (bookings || []).slice(0, 5);
  const currentSegment = segments && segments[0];
  const hasValidTicketForRoute = latestAlert && bookings && bookings.some(function(b) {
    return (b.status === 'CONFIRMED' || b.status === 'PAID') && b.trip && b.trip.fromStation === latestAlert.fromStation && b.trip.toStation === latestAlert.toStation;
  });
  const greeting = user && user.email ? 'Hi, ' + user.email.split('@')[0] : 'Hi';
  const fromStation = (latestAlert && latestAlert.fromStation) || (currentSegment && currentSegment.originStation) || '-';
  const toStation = (latestAlert && latestAlert.toStation) || (currentSegment && currentSegment.destinationStation) || '-';
  const ticketStatus = hasValidTicketForRoute ? 'valid' : (pendingStationEntryActions && pendingStationEntryActions.length ? 'pending' : 'no_ticket');

  const departuresByPlatform = React.useMemo(() => {
    const map = {};
    PLATFORM_ORDER.forEach((p) => { map[p] = []; });
    map.Other = [];
    (departures || []).forEach((t) => {
      const key = t.platform && PLATFORM_ORDER.includes(t.platform) ? t.platform : 'Other';
      map[key].push(t);
    });
    return map;
  }, [departures]);

  return (
    <div className="space-y-6 pb-24">
      <section>
        <h1 className="text-xl font-semibold text-slate-900">{greeting}</h1>
        {currentStation && (
          <div className="mt-2 rounded-lg border border-slate-200 bg-slate-50 p-3">
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Current station</p>
            <p className="text-lg font-semibold text-slate-900">{currentStation.displayName || currentStation.stationName}</p>
            <p className="text-xs text-slate-600 mt-0.5">Platforms: 1A, 1B, 2A, 2B, 3A, 3B, 4A, 4B, 5A, 5B</p>
            {onRefreshStation && (
              <button type="button" onClick={onRefreshStation} className="mt-2 text-xs text-blue-600 hover:underline">Refresh location</button>
            )}
          </div>
        )}
        {!currentStation && (
          <div className="mt-2 rounded-lg border border-slate-200 bg-slate-50 p-3">
            <p className="text-sm text-slate-600">Enable location to see your current station and departures.</p>
            {onEnableLocation && (
              <button
                type="button"
                onClick={onEnableLocation}
                className="mt-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"
              >
                Share location
              </button>
            )}
          </div>
        )}
        {latestAlert && latestAlert.fromStation && (
          <p className="text-sm text-slate-600 mt-0.5">Detected at: <span className="font-medium text-blue-600">{latestAlert.fromStation}</span></p>
        )}
      </section>

      {currentStation && (departures || []).length > 0 && (
        <section className="rounded-xl border-2 border-slate-200 bg-white p-4">
          <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide mb-3">Departures by platform</h2>
          <p className="text-sm text-slate-600 mb-4">Trains departing from {currentStation.displayName || currentStation.stationName}</p>
          <div className="space-y-4">
            {[...PLATFORM_ORDER, 'Other'].map((platformKey) => {
              const list = departuresByPlatform[platformKey] || [];
              if (list.length === 0) return null;
              return (
                <div key={platformKey} className="rounded-lg border border-slate-200 bg-slate-50/50 p-3">
                  <h3 className="text-sm font-semibold text-slate-800 mb-2">{platformKey === 'Other' ? 'Other' : `Platform ${platformKey}`}</h3>
                  <ul className="space-y-2">
                    {list.map((t) => (
                      <li key={t.id} className="flex items-center justify-between gap-2 text-sm flex-wrap">
                        <span className="font-medium text-slate-900">{t.trainName || t.trainCode}</span>
                        <span className="text-slate-600">{formatTime(t.departureTime)}</span>
                        <span className="text-slate-500">→ {t.toStation}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
          </div>
        </section>
      )}
      {currentStation && (!departures || departures.length === 0) && (
        <section className="rounded-xl border border-slate-200 bg-slate-50 p-4">
          <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide mb-2">Departures</h2>
          <p className="text-slate-600 text-sm">No upcoming departures from this station.</p>
        </section>
      )}
      {hasNoTicketWarning && <NoTicketBanner onBuyTicket={onBuyTicket} compact={false} />}
      {(latestAlert || currentSegment) && (
        <section className="rounded-xl border-2 border-blue-200 bg-blue-50/50 p-4 space-y-3">
          <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide">Detected route</h2>
          <p className="text-lg font-semibold text-slate-900">{fromStation} to {toStation}</p>
          <div>
            <span className="text-sm text-slate-600 mr-2">Ticket status</span>
            <TicketStatusBadge status={ticketStatus} />
          </div>
          <div className="flex gap-3 pt-2">
            <button type="button" onClick={onBuyTicket} className="flex-1 rounded-lg bg-blue-600 py-3 text-base font-semibold text-white hover:bg-blue-700">Buy ticket</button>
            <button type="button" onClick={onViewJourney} className="flex-1 rounded-lg border-2 border-blue-600 py-3 text-base font-semibold text-blue-600 hover:bg-blue-50">View journey details</button>
          </div>
        </section>
      )}
      <section>
        <h2 className="text-lg font-semibold text-slate-900 mb-3">Quick actions</h2>
        <button type="button" onClick={onBuyTicket} className="w-full rounded-xl border-2 border-slate-300 bg-white py-4 text-base font-semibold text-slate-800 hover:bg-slate-50">Buy ticket</button>
      </section>
      <section>
        <h2 className="text-lg font-semibold text-slate-900 mb-3">Recent journeys</h2>
        {recentBookings.length === 0 ? (
          <p className="text-slate-600 text-sm">No recent journeys. Buy a ticket to get started.</p>
        ) : (
          <ul className="space-y-2">
            {recentBookings.map(function(b) {
              return (
                <li key={b.id} className="rounded-lg border border-slate-200 bg-white p-3 flex justify-between items-center">
                  <div>
                    <p className="font-medium text-slate-900">{b.trip && b.trip.fromStation} to {b.trip && b.trip.toStation}</p>
                    <p className="text-xs text-slate-500">{formatDate(b.trip && b.trip.departureTime)}</p>
                  </div>
                  <span className={'text-sm font-medium ' + (b.status === 'CONFIRMED' || b.status === 'PAID' ? 'text-green-600' : 'text-slate-600')}>{b.status}</span>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}

export default HomeScreen;

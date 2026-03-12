import React, { useState } from 'react';
import MobileTicket from '../components/MobileTicket';
import ShowTicketByQR from '../components/ShowTicketByQR';

function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function TicketsScreen({ bookings, bookingsLoading, onBuyTicket }) {
  const [showQrPanel, setShowQrPanel] = useState(false);
  const activeTickets = (bookings ?? []).filter((b) => b.status === 'CONFIRMED' || b.status === 'PAID');
  const history = (bookings ?? []).filter((b) => b.status !== 'CONFIRMED' && b.status !== 'PAID');
  return (
    <div className="space-y-8 pb-24">
      <section>
        <div className="flex items-center justify-between gap-2 mb-3">
          <h2 className="text-lg font-semibold text-slate-900">Show ticket by QR</h2>
          <button
            type="button"
            onClick={() => setShowQrPanel((v) => !v)}
            className="text-sm font-medium text-blue-600 hover:text-blue-800"
          >
            {showQrPanel ? 'Hide' : 'Upload QR / Enter ID'}
          </button>
        </div>
        {showQrPanel && <ShowTicketByQR onClose={() => setShowQrPanel(false)} />}
      </section>
      <section>
        <h2 className="text-lg font-semibold text-slate-900 mb-3">Active tickets</h2>
        {bookingsLoading ? (
          <div className="rounded-xl border border-slate-200 bg-slate-50 p-6 text-center">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-400 border-t-transparent mx-auto" />
            <p className="text-slate-600 mt-2 text-sm">Loading tickets…</p>
          </div>
        ) : activeTickets.length === 0 ? (
          <div className="rounded-xl border border-slate-200 bg-slate-50 p-6 text-center">
            <p className="text-slate-600">No active tickets.</p>
            <button
              type="button"
              onClick={onBuyTicket}
              className="mt-3 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700"
            >
              Buy ticket
            </button>
          </div>
        ) : (
          <ul className="space-y-3">
            {activeTickets.map((b) => (
              <li key={b.id}>
                <MobileTicket booking={b} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2 className="text-lg font-semibold text-slate-900 mb-3">Travel history</h2>
        <p className="text-sm text-slate-600 mb-3">Date, route, cost, status and payment confirmation.</p>
        {history.length === 0 ? (
          <p className="text-slate-500 text-sm">No past bookings.</p>
        ) : (
          <ul className="space-y-2">
            {history.map((b) => (
              <li
                key={b.id}
                className="rounded-lg border border-slate-200 bg-white p-3 flex flex-wrap items-center justify-between gap-2"
              >
                <div>
                  <p className="font-medium text-slate-900">{b.trip?.fromStation} → {b.trip?.toStation}</p>
                  <p className="text-xs text-slate-500">{formatDate(b.trip?.departureTime)} · £{Number(b.amount || 0).toFixed(2)}</p>
                </div>
                <span className={`text-xs font-medium px-2 py-0.5 rounded ${
                  b.status === 'REFUNDED' ? 'bg-slate-100 text-slate-700' :
                  b.status === 'CANCELLED' || b.status === 'EXPIRED' ? 'bg-red-100 text-red-800' : 'bg-slate-100 text-slate-600'
                }`}>
                  {b.status}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

export default TicketsScreen;

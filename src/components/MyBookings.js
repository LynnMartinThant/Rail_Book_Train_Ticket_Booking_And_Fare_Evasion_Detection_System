import React, { useState } from 'react';
import * as api from '../api/client';

const REFUND_WINDOW_MS = 3 * 60 * 1000;

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

function canRequestRefund(r) {
  if (r.status !== 'PAID' || !r.updatedAt) return false;
  const paidAt = new Date(r.updatedAt).getTime();
  return Date.now() - paidAt < REFUND_WINDOW_MS;
}

function MyBookings({ bookings, loading, onBack, onRefundRequested }) {
  const [refundingId, setRefundingId] = useState(null);
  const [refundError, setRefundError] = useState(null);

  const handleRequestRefund = async (reservationId) => {
    setRefundError(null);
    setRefundingId(reservationId);
    try {
      await api.requestRefund(reservationId);
      onRefundRequested?.();
    } catch (e) {
      setRefundError(e.message);
    } finally {
      setRefundingId(null);
    }
  };
  if (loading) {
    return (
      <div className="flex justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-400 border-t-transparent" />
      </div>
    );
  }

  const hasBookings = bookings?.length > 0;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-900">My bookings</h2>
        {onBack && (
          <button
            type="button"
            onClick={onBack}
            className="text-sm text-gray-600 hover:text-black"
          >
            ← Back to trips
          </button>
        )}
      </div>

      {!hasBookings ? (
        <div className="rounded-xl border border-gray-200 bg-white p-8 text-center text-gray-600 shadow-sm">
          <p>You have no bookings yet.</p>
          <p className="mt-1 text-sm">Select a journey from the trip list to book seats.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {refundError && (
            <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              {refundError}
            </div>
          )}
          {bookings.map((r) => (
            <div
              key={r.id}
              className="rounded-xl border border-gray-200 bg-white p-4 shadow-sm"
            >
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <p className="font-medium text-gray-900">
                    {(r.journeyFromStation && r.journeyToStation) ? `${r.journeyFromStation} → ${r.journeyToStation}` : (r.trip ? `${r.trip.fromStation} → ${r.trip.toStation}` : '')}
                  </p>
                  <p className="text-sm text-gray-600">
                    {r.trip?.trainName} · {formatDate(r.trip?.departureTime)}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {statusBadge(r.status)}
                  <span className="font-semibold text-black">£{Number(r.amount).toFixed(2)}</span>
                </div>
              </div>
              <div className="mt-2 flex flex-wrap gap-2 text-sm text-gray-600">
                {r.seats?.map((s) => (
                  <span key={s.seatId} className="rounded bg-gray-100 px-2 py-0.5">
                    Seat {s.seatNumber}
                  </span>
                ))}
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <p className="text-xs text-gray-500">
                  Booked {formatDate(r.createdAt)}
                  {r.paymentReference && ` · Payment ID: ${r.paymentReference}`}
                </p>
                {canRequestRefund(r) && (
                  <button
                    type="button"
                    onClick={() => handleRequestRefund(r.id)}
                    disabled={refundingId === r.id}
                    className="rounded border border-amber-400 bg-amber-50 px-2 py-1 text-xs font-medium text-amber-800 hover:bg-amber-100 disabled:opacity-50"
                  >
                    {refundingId === r.id ? 'Requesting…' : 'Request refund (within 3 min)'}
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default MyBookings;

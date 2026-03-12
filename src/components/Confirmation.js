import React from 'react';

function Confirmation({ reservations, confirmed, loading, onConfirm, onStartOver }) {
  const source = confirmed?.length ? confirmed : reservations;
  const trip = source?.[0]?.trip;
  const seats = source?.flatMap((r) => r.seats || []).map((s) => s.seatNumber).join(', ') || '';
  const total = source?.reduce((sum, r) => sum + Number(r.amount || 0), 0) || 0;
  const allPaid = reservations?.every((r) => r.status === 'PAID');
  const allConfirmed = confirmed?.length > 0;

  if (allConfirmed) {
    return (
      <div className="space-y-6">
        <div className="rounded-xl border border-gray-200 bg-white p-8 text-center shadow-sm">
          <h2 className="text-xl font-semibold text-black">Booking confirmed</h2>
          <p className="mt-2 text-gray-900">
            {trip?.fromStation} → {trip?.toStation}
          </p>
          <p className="text-gray-600">Seats: {seats}</p>
          <p className="mt-2 text-lg font-semibold text-black">Total paid: £{total.toFixed(2)}</p>
          <p className="mt-4 text-sm text-gray-500">
            Payment ID(s): {confirmed.map((r) => r.paymentReference).filter(Boolean).join(', ') || '—'}
          </p>
        </div>
        <button
          type="button"
          onClick={onStartOver}
          className="rounded-lg bg-black px-4 py-2 font-medium text-white hover:bg-gray-800"
        >
          Book another journey
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold text-gray-900">Confirm booking</h2>

      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <p className="text-gray-900 font-medium">
          {trip?.fromStation} → {trip?.toStation}
        </p>
        <p className="text-gray-600">Seats: {seats}</p>
        <p className="mt-2 text-lg font-semibold text-black">Total: £{total.toFixed(2)} (paid)</p>
      </div>

      <p className="text-sm text-gray-600">
        Your payment has been received. Confirm to finalise your booking.
      </p>

      <div className="flex gap-4">
        <button
          type="button"
          onClick={onConfirm}
          disabled={loading || !allPaid}
          className="rounded-lg bg-black px-4 py-2 font-medium text-white disabled:opacity-50 hover:bg-gray-800"
        >
          {loading ? 'Confirming…' : 'Confirm booking'}
        </button>
      </div>
    </div>
  );
}

export default Confirmation;

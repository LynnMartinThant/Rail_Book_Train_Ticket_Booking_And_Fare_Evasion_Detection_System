import React, { useState } from 'react';

function formatDate(iso) {
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function SeatSelect({ trip, seats, loading, onReserve, onBack, onRetry }) {
  const [selected, setSelected] = useState(new Set());
  const hasSeats = Array.isArray(seats) && seats.length > 0;

  const toggle = (seatId, available) => {
    if (!available) return;
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(seatId)) next.delete(seatId);
      else next.add(seatId);
      return next;
    });
  };

  const handleReserve = () => {
    if (selected.size === 0) return;
    onReserve(Array.from(selected));
  };

  if (!trip) return null;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <button
          type="button"
          onClick={onBack}
          className="text-gray-600 hover:text-black"
        >
          ← Back
        </button>
        <div>
          <h2 className="text-lg font-semibold text-gray-900">
            {trip.fromStation} → {trip.toStation}
          </h2>
          <p className="text-sm text-gray-600">
            {formatDate(trip.departureTime)} · £{Number(trip.pricePerSeat).toFixed(2)} per seat
          </p>
        </div>
      </div>

      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <h3 className="mb-3 text-sm font-medium text-gray-700">Choose seats</h3>
        {loading ? (
          <div className="flex justify-center py-12">
            <div className="h-8 w-8 animate-spin rounded-full border-2 border-gray-400 border-t-transparent" />
          </div>
        ) : !hasSeats ? (
          <div className="rounded-xl border border-gray-200 bg-gray-50 p-8 text-center">
            <p className="text-gray-600 mb-4">No seats loaded for this trip.</p>
            <p className="text-sm text-gray-500 mb-4">Ensure the booking-service backend is running and has been started at least once to seed data.</p>
            {onRetry && (
              <button
                type="button"
                onClick={onRetry}
                className="rounded-lg bg-black px-4 py-2 font-medium text-white hover:bg-gray-800"
              >
                Retry
              </button>
            )}
          </div>
        ) : (
          <>
            <div className="mb-4 flex flex-wrap gap-4 rounded-lg border border-gray-200 bg-gray-50 p-3 text-sm text-gray-700">
              <span className="flex items-center gap-2">
                <span className="h-6 w-6 rounded border border-gray-300 bg-white" />
                Available
              </span>
              <span className="flex items-center gap-2">
                <span className="h-6 w-6 rounded border-2 border-black bg-gray-100" />
                Selected
              </span>
              <span className="flex items-center gap-2">
                <span className="h-6 w-6 rounded border border-gray-300 bg-gray-300 opacity-60" />
                Booked
              </span>
            </div>
            <div className="grid grid-cols-5 gap-2 sm:grid-cols-10">
              {seats.map((seat) => (
                <button
                  key={seat.seatId}
                  type="button"
                  disabled={!seat.available}
                  onClick={() => toggle(seat.seatId, seat.available)}
                  className={`
                    h-10 rounded-lg border text-sm font-medium transition
                    ${!seat.available
                      ? 'cursor-not-allowed border-gray-200 bg-gray-200 text-gray-500'
                      : selected.has(seat.seatId)
                        ? 'border-black bg-gray-100 text-black'
                        : 'border-gray-300 bg-white text-gray-900 hover:border-gray-400'
                    }
                  `}
                >
                  {seat.seatNumber}
                </button>
              ))}
            </div>
          </>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-4 border-t border-gray-200 pt-6">
        <button
          type="button"
          onClick={onBack}
          className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-gray-700 hover:bg-gray-50"
        >
          Back
        </button>
        <button
          type="button"
          onClick={handleReserve}
          disabled={selected.size === 0 || loading}
          className="rounded-lg bg-black px-4 py-2 font-medium text-white disabled:opacity-50 hover:bg-gray-800"
        >
          Reserve {selected.size > 0 ? `${selected.size} seat(s)` : 'seats'}
        </button>
      </div>
    </div>
  );
}

export default SeatSelect;

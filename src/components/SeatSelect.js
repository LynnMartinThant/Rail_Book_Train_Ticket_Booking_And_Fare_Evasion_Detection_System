import React, { useState, useMemo } from 'react';

const COL_LETTERS = ['A', 'B', 'C', 'D'];

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

/** Map flat seat index to grid label e.g. 0 -> "1A", 5 -> "2B" */
function indexToLabel(index) {
  const row = Math.floor(index / 4) + 1;
  const col = index % 4;
  return `${row}${COL_LETTERS[col]}`;
}

function SeatSelect({ trip, seats, loading, onReserve, onBack, onRetry }) {
  const [selected, setSelected] = useState(new Set());
  const hasSeats = Array.isArray(seats) && seats.length > 0;

  const availableCount = useMemo(() => (seats || []).filter((s) => s.available).length, [seats]);

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

  const selectedList = useMemo(() => Array.from(selected), [selected]);
  const selectedSeatNumbers = useMemo(() => {
    return selectedList
      .map((id) => {
        const idx = (seats || []).findIndex((s) => s.seatId === id);
        return idx >= 0 ? indexToLabel(idx) : null;
      })
      .filter(Boolean)
      .sort()
      .join(', ');
  }, [selectedList, seats]);

  const selectedIdToP = useMemo(() => {
    const map = new Map();
    selectedList.forEach((id, i) => map.set(id, i + 1));
    return map;
  }, [selectedList]);

  const carName = trip?.trainName ? `${trip.trainName}` : 'Executive 4';
  const carNumber = trip?.trainCode || '4';

  if (!trip) return null;

  return (
    <div className="min-h-screen flex flex-col bg-[#f5f5f5]">
      {/* Header: back, title "Choose Seat" with green underline */}
      <header className="flex-shrink-0 border-b border-gray-200 bg-white px-4 pt-3 pb-3">
        <div className="flex items-center justify-between">
          <button
            type="button"
            onClick={onBack}
            className="flex h-10 w-10 items-center justify-center rounded-full text-gray-600 hover:bg-gray-100 hover:text-gray-900"
            aria-label="Back"
          >
            <span className="text-xl leading-none">←</span>
          </button>
          <h1 className="text-lg font-bold text-gray-900">Choose Seat</h1>
          <span className="w-10" />
        </div>
        <div className="mt-2 border-b-2 border-emerald-500 w-12 mx-auto" aria-hidden />
      </header>

      <div className="flex flex-1 min-h-0">
        {/* Main content: car info, legend, seat grid */}
        <div className="flex-1 min-w-0 px-4 py-4 flex flex-col">
          {loading ? (
            <div className="flex flex-1 items-center justify-center py-12">
              <div className="h-10 w-10 animate-spin rounded-full border-2 border-gray-300 border-t-emerald-500" />
            </div>
          ) : !hasSeats ? (
            <div className="rounded-2xl border border-gray-200 bg-white p-8 text-center shadow-sm">
              <p className="text-gray-600 mb-4">No seats loaded for this trip.</p>
              <p className="text-sm text-gray-500 mb-4">Ensure the booking-service backend is running and has been started at least once to seed data.</p>
              {onRetry && (
                <button type="button" onClick={onRetry} className="rounded-xl bg-gray-900 px-4 py-2 font-medium text-white hover:bg-gray-800">
                  Retry
                </button>
              )}
            </div>
          ) : (
            <>
              {/* Car info + 360 View */}
              <div className="flex items-end justify-between mb-2">
                <div>
                  <h2 className="text-xl font-bold text-gray-900">{carName}</h2>
                  <p className="text-sm text-gray-500 mt-0.5">{availableCount} left</p>
                </div>
                <button
                  type="button"
                  className="rounded-xl border border-gray-300 bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-200"
                >
                  360 View
                </button>
              </div>

              {/* Legend */}
              <div className="flex flex-wrap gap-4 mb-4 text-sm text-gray-600">
                <span className="flex items-center gap-2">
                  <span className="h-5 w-5 rounded-full bg-gray-300" />
                  Not available
                </span>
                <span className="flex items-center gap-2">
                  <span className="h-5 w-5 rounded-full border border-gray-300 bg-white" />
                  Available
                </span>
                <span className="flex items-center gap-2">
                  <span className="h-5 w-5 rounded-full bg-gray-800" />
                  Selected
                </span>
              </div>

              {/* Seat grid in rounded container */}
              <div
                className="rounded-2xl bg-gray-200/80 p-4 flex-1 min-h-0 overflow-auto"
                style={{ '--seat-size': '2.5rem', '--seat-gap': '0.5rem' }}
              >
                <div className="inline-block min-w-0">
                  {/* Column headers A B | C D */}
                  <div className="flex items-center mb-1 gap-2">
                    <div className="flex gap-2 w-[calc(2*var(--seat-size)+var(--seat-gap))]">
                      <span className="w-[var(--seat-size)] text-center text-sm font-medium text-gray-700">A</span>
                      <span className="w-[var(--seat-size)] text-center text-sm font-medium text-gray-700">B</span>
                    </div>
                    <div className="w-4" />
                    <div className="flex gap-2 w-[calc(2*var(--seat-size)+var(--seat-gap))]">
                      <span className="w-[var(--seat-size)] text-center text-sm font-medium text-gray-700">C</span>
                      <span className="w-[var(--seat-size)] text-center text-sm font-medium text-gray-700">D</span>
                    </div>
                  </div>

                  {/* Rows */}
                  {(() => {
                    const rows = Math.ceil(seats.length / 4);
                    const rowEls = [];
                    for (let r = 0; r < rows; r++) {
                      const rowSeats = [
                        seats[r * 4],
                        seats[r * 4 + 1],
                        seats[r * 4 + 2],
                        seats[r * 4 + 3],
                      ].filter(Boolean);
                      rowEls.push(
                        <div key={r} className="flex items-center gap-2 mb-2">
                          <div className="flex gap-2 w-[calc(2*var(--seat-size)+var(--seat-gap))]">
                            {[0, 1].map((c) => {
                              const seat = rowSeats[c];
                              if (!seat) return <span key={c} className="w-[var(--seat-size)] h-[var(--seat-size)]" />;
                              const label = indexToLabel(r * 4 + c);
                              const isSelected = selected.has(seat.seatId);
                              const pNum = isSelected ? selectedIdToP.get(seat.seatId) : null;
                              return (
                                <button
                                  key={seat.seatId}
                                  type="button"
                                  disabled={!seat.available}
                                  onClick={() => toggle(seat.seatId, seat.available)}
                                  className={`
                                    w-[var(--seat-size)] h-[var(--seat-size)] rounded-xl text-sm font-medium transition flex items-center justify-center shrink-0
                                    ${!seat.available
                                      ? 'cursor-not-allowed bg-gray-300 text-gray-500'
                                      : isSelected
                                        ? 'bg-gray-800 text-white'
                                        : 'bg-white border border-gray-300 text-gray-800 hover:border-gray-400'
                                    }
                                  `}
                                >
                                  {isSelected ? `P${pNum}` : label}
                                </button>
                              );
                            })}
                          </div>
                          <div className="w-4 shrink-0" />
                          <div className="flex gap-2 w-[calc(2*var(--seat-size)+var(--seat-gap))]">
                            {[2, 3].map((c) => {
                              const seat = rowSeats[c];
                              if (!seat) return <span key={c} className="w-[var(--seat-size)] h-[var(--seat-size)]" />;
                              const label = indexToLabel(r * 4 + c);
                              const isSelected = selected.has(seat.seatId);
                              const pNum = isSelected ? selectedIdToP.get(seat.seatId) : null;
                              return (
                                <button
                                  key={seat.seatId}
                                  type="button"
                                  disabled={!seat.available}
                                  onClick={() => toggle(seat.seatId, seat.available)}
                                  className={`
                                    w-[var(--seat-size)] h-[var(--seat-size)] rounded-xl text-sm font-medium transition flex items-center justify-center shrink-0
                                    ${!seat.available
                                      ? 'cursor-not-allowed bg-gray-300 text-gray-500'
                                      : isSelected
                                        ? 'bg-gray-800 text-white'
                                        : 'bg-white border border-gray-300 text-gray-800 hover:border-gray-400'
                                    }
                                  `}
                                >
                                  {isSelected ? `P${pNum}` : label}
                                </button>
                              );
                            })}
                          </div>
                          <span className="w-6 text-right text-sm font-medium text-gray-600 ml-1">{r + 1}</span>
                        </div>
                      );
                    }
                    return rowEls;
                  })()}
                </div>
              </div>
            </>
          )}
        </div>

        {/* Car selector strip (right) */}
        {hasSeats && !loading && (
          <div className="flex-shrink-0 w-12 flex flex-col items-center py-4 gap-2">
            <div className="rounded-xl bg-gray-200/80 p-1.5 flex flex-col gap-1.5">
              {[4, 5, 6, 7, 8].map((num) => (
                <button
                  key={num}
                  type="button"
                  className={`
                    w-9 h-9 rounded-lg text-sm font-semibold transition
                    ${num === Number(carNumber) || (carNumber === '4' && num === 4)
                      ? 'bg-gray-800 text-white'
                      : 'bg-white text-gray-700 hover:bg-gray-100'
                    }
                  `}
                >
                  {num}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Bottom bar: Car Number, Seat Number, NEXT */}
      {hasSeats && !loading && (
        <div className="flex-shrink-0 rounded-t-2xl bg-white border-t border-gray-200 shadow-lg px-4 py-4 safe-area-pb">
          <div className="flex items-center justify-between gap-4">
            <div className="min-w-0">
              <p className="text-xs text-gray-500">Car Number</p>
              <p className="font-bold text-gray-900 truncate">{carName}</p>
              <p className="text-xs text-gray-500 mt-1">Seat Number</p>
              <p className="font-bold text-gray-900 truncate">{selectedSeatNumbers || '—'}</p>
            </div>
            <button
              type="button"
              onClick={handleReserve}
              disabled={selected.size === 0}
              className="flex-shrink-0 rounded-xl bg-emerald-500 px-6 py-3 font-bold text-white shadow-md disabled:opacity-50 disabled:cursor-not-allowed hover:bg-emerald-600 active:bg-emerald-700"
            >
              NEXT
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export default SeatSelect;

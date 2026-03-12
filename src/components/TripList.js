import React, { useMemo, useState, useEffect } from 'react';

const DURATION_MINUTES = 5; // Hallam Line Leeds → Sheffield

/** Stations by line (value = API fromStation/toStation). Hallam Line has geofences. */
const STATIONS_BY_LINE = [
  {
    line: 'Hallam Line (Leeds → Sheffield)',
    stations: [
      { value: 'Leeds', label: 'Leeds railway station' },
      { value: 'Woodlesford', label: 'Woodlesford' },
      { value: 'Castleford', label: 'Castleford' },
      { value: 'Normanton', label: 'Normanton' },
      { value: 'Wakefield Kirkgate', label: 'Wakefield Kirkgate railway station' },
      { value: 'Darton', label: 'Darton' },
      { value: 'Barnsley', label: 'Barnsley railway station' },
      { value: 'Wombwell', label: 'Wombwell' },
      { value: 'Elsecar', label: 'Elsecar' },
      { value: 'Chapeltown', label: 'Chapeltown' },
      { value: 'Meadowhall Interchange', label: 'Meadowhall Interchange' },
      { value: 'Sheffield', label: 'Sheffield railway station' },
    ],
  },
  {
    line: 'Calder Valley (Leeds → Manchester Victoria)',
    stations: [
      { value: 'Leeds', label: 'Leeds railway station' },
      { value: 'Bramley', label: 'Bramley' },
      { value: 'New Pudsey', label: 'New Pudsey' },
      { value: 'Bradford Interchange', label: 'Bradford Interchange' },
      { value: 'Low Moor', label: 'Low Moor' },
      { value: 'Halifax', label: 'Halifax railway station' },
      { value: 'Sowerby Bridge', label: 'Sowerby Bridge' },
      { value: 'Mytholmroyd', label: 'Mytholmroyd' },
      { value: 'Hebden Bridge', label: 'Hebden Bridge' },
      { value: 'Todmorden', label: 'Todmorden' },
      { value: 'Walsden', label: 'Walsden' },
      { value: 'Littleborough', label: 'Littleborough' },
      { value: 'Smithy Bridge', label: 'Smithy Bridge' },
      { value: 'Rochdale', label: 'Rochdale railway station' },
      { value: 'Manchester Victoria', label: 'Manchester Victoria station' },
    ],
  },
  {
    line: 'Hope Valley (Sheffield → Manchester Piccadilly)',
    stations: [
      { value: 'Sheffield', label: 'Sheffield railway station' },
      { value: 'Dore & Totley', label: 'Dore & Totley' },
      { value: 'Grindleford', label: 'Grindleford' },
      { value: 'Hathersage', label: 'Hathersage' },
      { value: 'Bamford', label: 'Bamford' },
      { value: 'Hope', label: 'Hope' },
      { value: 'Edale', label: 'Edale' },
      { value: 'Chinley', label: 'Chinley railway station' },
      { value: 'New Mills Central', label: 'New Mills Central' },
      { value: 'Marple', label: 'Marple' },
      { value: 'Manchester Piccadilly', label: 'Manchester Piccadilly station' },
    ],
  },
  {
    line: 'Leeds → Manchester via Huddersfield',
    stations: [
      { value: 'Leeds', label: 'Leeds railway station' },
      { value: 'Cross Gates', label: 'Cross Gates' },
      { value: 'Garforth', label: 'Garforth' },
      { value: 'East Garforth', label: 'East Garforth' },
      { value: 'Micklefield', label: 'Micklefield' },
      { value: 'Church Fenton', label: 'Church Fenton' },
      { value: 'Huddersfield', label: 'Huddersfield railway station' },
      { value: 'Slaithwaite', label: 'Slaithwaite' },
      { value: 'Marsden', label: 'Marsden' },
      { value: 'Greenfield', label: 'Greenfield' },
      { value: 'Mossley', label: 'Mossley' },
      { value: 'Stalybridge', label: 'Stalybridge' },
      { value: 'Manchester Victoria', label: 'Manchester Victoria station' },
    ],
  },
  {
    line: 'Sheffield → Leeds via Wakefield (fast)',
    stations: [
      { value: 'Sheffield', label: 'Sheffield railway station' },
      { value: 'Meadowhall Interchange', label: 'Meadowhall Interchange' },
      { value: 'Rotherham Central', label: 'Rotherham Central' },
      { value: 'Swinton', label: 'Swinton' },
      { value: 'Moorthorpe', label: 'Moorthorpe' },
      { value: 'Wakefield Kirkgate', label: 'Wakefield Kirkgate' },
      { value: 'Woodlesford', label: 'Woodlesford' },
      { value: 'Leeds', label: 'Leeds railway station' },
    ],
  },
];

function formatTime(iso) {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
}

function formatDateShort(d) {
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}

function dayKey(iso) {
  const d = new Date(iso);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function TripList({ trips, loading, onSelectTrip, onCancel, fromStation, toStation, onFromToChange }) {
  const [selectedDateKey, setSelectedDateKey] = useState(() => {
    const t = new Date();
    t.setDate(t.getDate() + 1);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
  });
  const [sortBy, setSortBy] = useState('earliest'); // 'earliest' | 'price' | 'duration'

  const handleFromChange = (e) => {
    const v = e.target.value;
    if (onFromToChange) onFromToChange(v, toStation);
  };
  const handleToChange = (e) => {
    const v = e.target.value;
    if (onFromToChange) onFromToChange(fromStation, v);
  };

  // When trips load, if selected date has no trips, switch to a date that has trips (only when trips change)
  useEffect(() => {
    if (!trips?.length) return;
    const byDate = {};
    trips.forEach((t) => {
      const k = dayKey(t.departureTime);
      byDate[k] = (byDate[k] || 0) + 1;
    });
    const countOnSelected = byDate[selectedDateKey] || 0;
    if (countOnSelected === 0) {
      const bestDate = Object.entries(byDate).sort((a, b) => b[1] - a[1])[0]?.[0];
      if (bestDate) setSelectedDateKey(bestDate);
    }
  // Intentionally omit selectedDateKey so we only run when trips change and don't override user's date pick
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trips]);

  const dateOptions = useMemo(() => {
    const out = [];
    const base = new Date();
    for (let i = 0; i < 3; i++) {
      const d = new Date(base);
      d.setDate(d.getDate() + i);
      out.push({
        key: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`,
        label: formatDateShort(d),
        date: d,
      });
    }
    return out;
  }, []);

  const filteredAndSortedTrips = useMemo(() => {
    if (!trips?.length) return [];
    const list = trips.filter((t) => dayKey(t.departureTime) === selectedDateKey);
    const sorted = [...list].sort((a, b) => {
      if (sortBy === 'earliest') return new Date(a.departureTime) - new Date(b.departureTime);
      if (sortBy === 'price') return Number(a.pricePerSeat) - Number(b.pricePerSeat);
      return 0; // duration same for all
    });
    return sorted;
  }, [trips, selectedDateKey, sortBy]);

  const minPrice = useMemo(() => {
    if (!trips?.length) return null;
    return Math.min(...trips.map((t) => Number(t.pricePerSeat)));
  }, [trips]);

  if (loading) {
    return (
      <div className="flex justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-400 border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="flex flex-col min-h-[80vh]">
      {/* Header: dark blue, Trip.com style */}
      <header className="bg-[#0d3b66] text-white px-4 pt-4 pb-3 -mx-4 -mt-6 mb-0">
        <div className="flex items-center justify-between mb-3">
          <span className="w-16 text-left">
            {onCancel && (
              <button type="button" onClick={onCancel} className="text-white/90 hover:text-white text-sm font-medium">Cancel</button>
            )}
          </span>
          <span className="text-lg font-bold">RailBook</span>
          <span className="w-16 text-right text-sm font-medium">GBP</span>
        </div>
        {/* Station picker: From / To (all lines) */}
        <div className="grid grid-cols-2 gap-2 mt-2">
          <div>
            <label className="block text-xs text-white/70 mb-0.5">From</label>
            <select
              value={fromStation ?? 'Leeds'}
              onChange={handleFromChange}
              className="w-full rounded bg-white/15 text-white border border-white/30 px-2 py-1.5 text-sm focus:ring-2 focus:ring-white/50 focus:border-white"
              aria-label="Departure station"
            >
              {STATIONS_BY_LINE.map((group) => (
                <optgroup key={group.line} label={group.line}>
                  {group.stations.map((s) => (
                    <option key={s.value} value={s.value} className="text-slate-900">{s.label}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs text-white/70 mb-0.5">To</label>
            <select
              value={toStation ?? 'Sheffield'}
              onChange={handleToChange}
              className="w-full rounded bg-white/15 text-white border border-white/30 px-2 py-1.5 text-sm focus:ring-2 focus:ring-white/50 focus:border-white"
              aria-label="Arrival station"
            >
              {STATIONS_BY_LINE.map((group) => (
                <optgroup key={group.line} label={group.line}>
                  {group.stations.map((s) => (
                    <option key={s.value} value={s.value} className="text-slate-900">{s.label}</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </div>
        </div>
        <p className="text-sm text-white/90 mt-2">{fromStation ?? 'From'} (Any) → {toStation ?? 'To'}</p>
        <div className="flex gap-4 mt-3 border-b border-white/20 -mb-px">
          <button type="button" className="pb-2 border-b-2 border-white font-medium text-sm flex items-center gap-1.5">
            <span aria-hidden>🚂</span> Trains {minPrice != null && <span className="text-white/90">From £{minPrice.toFixed(2)}</span>}
          </button>
          <button type="button" className="pb-2 text-white/70 text-sm flex items-center gap-1.5">
            <span aria-hidden>🚌</span> Coaches
          </button>
        </div>
      </header>

      {/* Date strip */}
      <div className="flex gap-2 mt-4 overflow-x-auto pb-2">
        {dateOptions.map((opt) => (
          <button
            key={opt.key}
            type="button"
            onClick={() => setSelectedDateKey(opt.key)}
            className={`shrink-0 px-4 py-2 rounded-lg text-sm font-medium ${
              selectedDateKey === opt.key ? 'bg-[#0d3b66] text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>

      {/* No booking fee + date row */}
      <div className="mt-2 rounded-lg bg-pink-100 text-pink-800 px-3 py-2 text-sm font-medium">No booking fee</div>
      <div className="flex items-center justify-between mt-3 text-sm text-slate-600">
        <span>{dateOptions.find((d) => d.key === selectedDateKey)?.label ?? selectedDateKey} | 1</span>
        <label className="flex items-center gap-2">
          <span>Open return</span>
          <input type="checkbox" className="rounded border-slate-300" />
        </label>
      </div>

      {/* Earlier trains collapse */}
      {filteredAndSortedTrips.length > 3 && (
        <button type="button" className="mt-2 text-sm text-slate-500 hover:text-slate-700 flex items-center gap-1">
          Earlier trains <span className="text-xs">^</span>
        </button>
      )}

      {/* Ticket cards */}
      <div className="mt-4 space-y-3 flex-1">
        {!trips?.length ? (
          <div className="rounded-xl border border-slate-200 bg-white p-8 text-center text-slate-600">
            <p className="font-medium">No trips available.</p>
            <p className="mt-2 text-sm">
              Start the backend: <code className="rounded bg-slate-100 px-1.5 py-0.5">cd booking-service &amp;&amp; mvn spring-boot:run</code>
            </p>
            <p className="mt-2 text-sm">
              If it’s already running, reset data: set <code className="rounded bg-slate-100 px-1 py-0.5">booking.data.reset-on-startup: true</code> in <code className="rounded bg-slate-100 px-1 py-0.5">application.yml</code>, restart the backend, then set it back to <code className="rounded bg-slate-100 px-1 py-0.5">false</code>.
            </p>
          </div>
        ) : filteredAndSortedTrips.length === 0 ? (
          <div className="rounded-xl border border-slate-200 bg-white p-6 text-center text-slate-600">
            <p className="font-medium">No trains on this date.</p>
            <p className="mt-1 text-sm">Try another date.</p>
          </div>
        ) : (
          filteredAndSortedTrips.map((trip) => (
            <TripCard key={trip.id} trip={trip} onSelect={onSelectTrip} />
          ))
        )}
      </div>

      {/* Bottom sort bar */}
      <div className="sticky bottom-0 mt-6 flex gap-2 justify-center border-t border-slate-200 bg-white py-3 -mx-4 px-4">
        <button
          type="button"
          onClick={() => setSortBy('price')}
          className={`px-4 py-2 rounded-lg text-sm font-medium flex items-center gap-1.5 ${
            sortBy === 'price' ? 'bg-[#0d3b66] text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
          }`}
        >
          <span aria-hidden>📊</span> By price
        </button>
        <button
          type="button"
          onClick={() => setSortBy('earliest')}
          className={`px-4 py-2 rounded-lg text-sm font-medium flex items-center gap-1.5 ${
            sortBy === 'earliest' ? 'bg-[#0d3b66] text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
          }`}
        >
          <span aria-hidden>🕐</span> Earliest
        </button>
        <button
          type="button"
          onClick={() => setSortBy('duration')}
          className={`px-4 py-2 rounded-lg text-sm font-medium flex items-center gap-1.5 ${
            sortBy === 'duration' ? 'bg-[#0d3b66] text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
          }`}
        >
          <span aria-hidden>⏱</span> By duration
        </button>
      </div>
    </div>
  );
}

function TripCard({ trip, onSelect }) {
  const dep = new Date(trip.departureTime);
  const arr = new Date(dep.getTime() + DURATION_MINUTES * 60 * 1000);
  const platform = trip.platform ? `Plat. ${trip.platform}` : '';

  return (
    <button
      type="button"
      onClick={() => onSelect(trip)}
      className="w-full rounded-xl border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:border-slate-300 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-[#0d3b66] focus:ring-offset-2"
    >
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center gap-2 min-w-0">
          <div>
            <p className="text-lg font-semibold text-slate-900">{formatTime(trip.departureTime)}</p>
            <p className="text-xs text-green-600 font-medium">On time</p>
            <p className="text-sm text-slate-600">{trip.fromStation}</p>
          </div>
          <span className="text-slate-400 shrink-0" aria-hidden>→</span>
          <div>
            <p className="text-lg font-semibold text-slate-900">{formatTime(arr)}</p>
            <p className="text-xs text-green-600 font-medium">On time</p>
            <p className="text-sm text-slate-600">{trip.toStation}</p>
          </div>
        </div>
        <p className="text-lg font-bold text-slate-900 shrink-0">£{Number(trip.pricePerSeat).toFixed(2)}</p>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-slate-500">
        {platform && <span>{platform}</span>}
        <span>{DURATION_MINUTES}m, direct</span>
        <span className="text-[#0d3b66] font-medium">Live tracker &gt;</span>
      </div>
    </button>
  );
}

export default TripList;

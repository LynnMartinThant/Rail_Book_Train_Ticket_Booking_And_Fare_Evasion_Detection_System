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
  if (iso == null) return '--:--';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '--:--';
  return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
}

function formatDateShort(d) {
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' });
}

function dayKey(iso) {
  if (iso == null) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const ACCENT = '#0d5c4c'; // dark teal to match "Choose Seat" layout

function TripList({ trips, loading, onSelectTrip, onCancel, fromStation, toStation, onFromToChange }) {
  const [selectedDateKey, setSelectedDateKey] = useState(() => {
    const t = new Date();
    t.setDate(t.getDate() + 1);
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`;
  });
  const [sortBy, setSortBy] = useState('earliest'); // 'earliest' | 'price' | 'duration'
  const [selectedTrip, setSelectedTrip] = useState(null);

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

  useEffect(() => {
    if (selectedTrip && filteredAndSortedTrips.length > 0 && !filteredAndSortedTrips.some((t) => t.id === selectedTrip.id)) {
      setSelectedTrip(null);
    }
  }, [filteredAndSortedTrips, selectedTrip]);

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

  const minPrice = useMemo(() => {
    if (!trips?.length) return null;
    return Math.min(...trips.map((t) => Number(t.pricePerSeat)));
  }, [trips]);

  const handleNext = () => {
    if (selectedTrip) onSelectTrip(selectedTrip);
  };

  if (loading) {
    return (
      <div className="flex justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-400 border-t-transparent" />
      </div>
    );
  }

  const routeLabel = `${fromStation ?? 'From'} → ${toStation ?? 'To'}`;
  const tripCount = filteredAndSortedTrips.length;

  return (
    <div className="flex flex-col min-h-[80vh] bg-white">
      {/* Header: back + title + accent line (Choose Seat style) */}
      <header className="border-b border-slate-200 bg-white px-4 pt-4 pb-2">
        <div className="flex items-center gap-3">
          {onCancel && (
            <button type="button" onClick={onCancel} className="p-1 -ml-1 text-slate-600 hover:text-slate-900" aria-label="Back">
              <span className="text-xl leading-none">←</span>
            </button>
          )}
          <h1 className="text-lg font-semibold text-slate-900">Select journey</h1>
        </div>
        <div className="h-0.5 w-24 rounded-full mt-2" style={{ backgroundColor: ACCENT }} aria-hidden />
      </header>

      {/* Route + count (like "Executive 4" / "36 left") */}
      <div className="px-4 pt-5 pb-2">
        <p className="text-xl font-bold text-slate-900">{routeLabel}</p>
        <p className="text-sm text-slate-500 mt-0.5">{tripCount} {tripCount === 1 ? 'trip' : 'trips'} available</p>
      </div>

      {/* From / To dropdowns - compact */}
      <div className="px-4 grid grid-cols-2 gap-2">
        <div>
          <label className="block text-xs text-slate-500 mb-0.5">From</label>
          <select
            value={fromStation ?? 'Leeds'}
            onChange={handleFromChange}
            className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:ring-2 focus:ring-offset-0 focus:border-slate-400"
            aria-label="Departure station"
            style={{ outlineColor: ACCENT }}
          >
            {STATIONS_BY_LINE.map((group) => (
              <optgroup key={group.line} label={group.line}>
                {group.stations.map((s) => (
                  <option key={s.value} value={s.value}>{s.label}</option>
                ))}
              </optgroup>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-xs text-slate-500 mb-0.5">To</label>
          <select
            value={toStation ?? 'Sheffield'}
            onChange={handleToChange}
            className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:ring-2 focus:ring-offset-0 focus:border-slate-400"
            aria-label="Arrival station"
            style={{ outlineColor: ACCENT }}
          >
            {STATIONS_BY_LINE.map((group) => (
              <optgroup key={group.line} label={group.line}>
                {group.stations.map((s) => (
                  <option key={s.value} value={s.value}>{s.label}</option>
                ))}
              </optgroup>
            ))}
          </select>
        </div>
      </div>

      {/* Legend + sort (like Not available / Available / Selected + 360 View) */}
      <div className="px-4 mt-4 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3 text-xs text-slate-600">
          <span className="flex items-center gap-1.5">
            <span className="w-5 h-5 rounded border border-slate-300 bg-white" />
            Available
          </span>
          <span className="flex items-center gap-1.5">
            <span className="w-5 h-5 rounded border-2 bg-slate-100" style={{ borderColor: ACCENT, backgroundColor: `${ACCENT}18` }} />
            Selected
          </span>
        </div>
        <div className="flex gap-1">
          {['earliest', 'price', 'duration'].map((key) => (
            <button
              key={key}
              type="button"
              onClick={() => setSortBy(key)}
              className={`px-3 py-1.5 rounded-lg text-xs font-medium ${sortBy === key ? 'text-white' : 'text-slate-600 bg-slate-100 hover:bg-slate-200'}`}
              style={sortBy === key ? { backgroundColor: ACCENT } : {}}
            >
              {key === 'earliest' ? 'Earliest' : key === 'price' ? 'Price' : 'Duration'}
            </button>
          ))}
        </div>
      </div>

      {/* Date strip (car selector style - pills) */}
      <div className="px-4 mt-3 flex gap-2 overflow-x-auto pb-2">
        {dateOptions.map((opt) => (
          <button
            key={opt.key}
            type="button"
            onClick={() => { setSelectedDateKey(opt.key); setSelectedTrip(null); }}
            className={`shrink-0 px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              selectedDateKey === opt.key ? 'text-white' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'
            }`}
            style={selectedDateKey === opt.key ? { backgroundColor: ACCENT } : {}}
          >
            {opt.label}
          </button>
        ))}
      </div>

      {/* Trip list (seat grid style - cards) */}
      <div className="px-4 mt-2 flex-1 space-y-2 pb-32">
        {!trips?.length ? (
          <div className="rounded-xl border border-slate-200 bg-slate-50 p-8 text-center text-slate-600">
            <p className="font-medium">No trips available.</p>
            <p className="mt-2 text-sm">
              Start the backend: <code className="rounded bg-slate-200 px-1.5 py-0.5">cd booking-service &amp;&amp; mvn spring-boot:run</code>
            </p>
          </div>
        ) : filteredAndSortedTrips.length === 0 ? (
          <div className="rounded-xl border border-slate-200 bg-slate-50 p-6 text-center text-slate-600">
            <p className="font-medium">No trains on this date.</p>
            <p className="mt-1 text-sm">Try another date.</p>
          </div>
        ) : (
          filteredAndSortedTrips.map((trip) => (
            <TripCard
              key={trip.id}
              trip={trip}
              segmentFrom={fromStation}
              segmentTo={toStation}
              selected={selectedTrip?.id === trip.id}
              onSelect={() => setSelectedTrip(trip)}
              accent={ACCENT}
            />
          ))
        )}
      </div>

      {/* Bottom bar: summary + NEXT (like Car Number / Seat Number + NEXT) */}
      <div className="fixed bottom-0 left-0 right-0 border-t border-slate-200 bg-white px-4 py-3 safe-area-pb">
        <div className="mx-auto max-w-4xl flex flex-wrap items-center justify-between gap-3">
          <div className="text-sm text-slate-600 min-w-0">
            <p className="font-medium text-slate-900">Route: {routeLabel}</p>
            <p className="text-slate-500 truncate">
              {selectedTrip
                ? `Departure: ${formatTime(selectedTrip?.departureTime)} · £${Number(selectedTrip?.pricePerSeat ?? 0).toFixed(2)}`
                : 'Select a train'}
            </p>
          </div>
          <button
            type="button"
            onClick={handleNext}
            disabled={!selectedTrip}
            className="shrink-0 rounded-xl px-6 py-3 font-semibold text-white disabled:opacity-50 disabled:cursor-not-allowed hover:opacity-95 transition-opacity"
            style={{ backgroundColor: selectedTrip ? ACCENT : '#94a3b8' }}
          >
            NEXT
          </button>
        </div>
      </div>
    </div>
  );
}

function TripCard({ trip, segmentFrom, segmentTo, selected, onSelect, accent }) {
  if (!trip) return null;
  const dep = new Date(trip.departureTime);
  const arr = Number.isNaN(dep.getTime()) ? dep : new Date(dep.getTime() + DURATION_MINUTES * 60 * 1000);
  const platform = trip.platform ? `Plat. ${trip.platform}` : '';
  const showSegment = segmentFrom && segmentTo && (segmentFrom !== trip.fromStation || segmentTo !== trip.toStation);
  const displayFrom = showSegment ? segmentFrom : trip.fromStation;
  const displayTo = showSegment ? segmentTo : trip.toStation;

  return (
    <button
      type="button"
      onClick={onSelect}
      className={`w-full rounded-xl border-2 p-4 text-left transition focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-[#0d5c4c] ${
        selected ? 'shadow-md' : 'border-slate-200 bg-white hover:border-slate-300 hover:shadow-sm'
      }`}
      style={
        selected
          ? { borderColor: accent, backgroundColor: `${accent}18` }
          : {}
      }
    >
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-center gap-2 min-w-0">
          <div>
            <p className="text-lg font-semibold text-slate-900">{formatTime(trip.departureTime)}</p>
            <p className="text-xs text-slate-500">On time</p>
            <p className="text-sm text-slate-600">{displayFrom}</p>
          </div>
          <span className="text-slate-400 shrink-0" aria-hidden>→</span>
          <div>
            <p className="text-lg font-semibold text-slate-900">{formatTime(arr)}</p>
            <p className="text-xs text-slate-500">On time</p>
            <p className="text-sm text-slate-600">{displayTo}</p>
          </div>
        </div>
        <p className="text-lg font-bold text-slate-900 shrink-0">£{Number(trip.pricePerSeat).toFixed(2)}</p>
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-slate-500">
        {showSegment && (
          <span className="text-xs text-slate-500">Train: {trip.fromStation} → {trip.toStation}</span>
        )}
        {platform && <span>{platform}</span>}
        <span>{DURATION_MINUTES}m, direct</span>
        {trip.fareTier && <span className="text-xs font-medium text-slate-600">{trip.fareTier}</span>}
      </div>
    </button>
  );
}

export default TripList;

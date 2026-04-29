import React, { useEffect, useMemo } from 'react';
import { MapContainer, TileLayer, Circle, CircleMarker, Popup, useMap } from 'react-leaflet';
import L from 'leaflet';

import 'leaflet/dist/leaflet.css';

const CENTER = [53.5, -1.7]; // UK North – fits Leeds, Sheffield, Manchester
const ZOOM = 8;

function toNum(v) {
  if (typeof v === 'number' && !Number.isNaN(v)) return v;
  const n = parseFloat(v);
  return typeof n === 'number' && !Number.isNaN(n) ? n : null;
}
function isValidCoord(g) {
  const lat = toNum(g?.latitude);
  const lon = toNum(g?.longitude);
  return lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
}

function normalizeStation(s) {
  if (s == null || s === '') return '';
  return String(s).trim().toLowerCase();
}

/** Match admin movement row to a geofence centre (no raw GPS in admin API). */
function matchGeofenceForStation(geofences, stationName) {
  const want = normalizeStation(stationName);
  if (!want) return null;
  const list = (geofences || []).filter(isValidCoord);
  const exact = list.find((g) => normalizeStation(g.stationName) === want || normalizeStation(g.name) === want);
  if (exact) return exact;
  return list.find((g) => {
    const sn = normalizeStation(g.stationName);
    const nm = normalizeStation(g.name);
    return (sn && want.includes(sn)) || (sn && sn.includes(want)) || (nm && want.includes(nm)) || (nm && nm.includes(want));
  }) || null;
}

function userMarkerPositions(geofences, userLocations) {
  const out = [];
  const stackAt = new Map();
  (userLocations || []).forEach((u) => {
    const g = matchGeofenceForStation(geofences, u.currentStation);
    if (!g) return;
    const lat = toNum(g.latitude);
    const lon = toNum(g.longitude);
    if (lat == null || lon == null) return;
    const gk = `${lat},${lon}`;
    const idx = stackAt.get(gk) || 0;
    stackAt.set(gk, idx + 1);
    const angle = (idx * 55 * Math.PI) / 180;
    const d = 0.00022 * (1 + Math.floor(idx / 6));
    out.push({
      userId: u.userId,
      station: u.currentStation,
      lat: lat + d * Math.cos(angle),
      lon: lon + d * Math.sin(angle),
      lastEvent: u.lastGeofenceEventType,
    });
  });
  return out;
}

function SetBounds({ geofences, userPositions, eventPoints }) {
  const map = useMap();
  const points = useMemo(() => {
    const fromGf = (geofences || []).filter(isValidCoord).map((g) => [g.latitude, g.longitude]);
    const fromUsers = (userPositions || []).map((p) => [p.lat, p.lon]);
    const fromEv = (eventPoints || []).map((p) => [p.lat, p.lon]);
    return [...fromGf, ...fromUsers, ...fromEv];
  }, [geofences, userPositions, eventPoints]);
  useEffect(() => {
    if (points.length > 0) {
      const bounds = L.latLngBounds(points);
      const t = setTimeout(() => {
        map.fitBounds(bounds.pad(0.15));
        map.invalidateSize();
      }, 100);
      return () => clearTimeout(t);
    }
  }, [map, points]);
  return null;
}

function StationPopup({ g }) {
  return (
    <div className="min-w-[200px] text-left">
      <p className="font-semibold text-slate-800">{g.name}</p>
      <p className="text-xs text-slate-500 mt-0.5">{g.stationName}</p>
      {g.platform && (
        <p className="text-xs text-slate-600 mt-1">Platform: {g.platform}</p>
      )}
      <p className="text-xs text-slate-500 mt-1">
        Lat: {Number(g.latitude).toFixed(5)}, Lon: {Number(g.longitude).toFixed(5)}
      </p>
      <p className="text-xs text-slate-500">Radius: {g.radiusMeters ?? 150} m</p>
    </div>
  );
}

function AdminGeofenceMap({
  geofences = [],
  userLocations = [],
  highlightedEvents = [],
  showUserMarkers = true,
}) {
  const validGeofences = useMemo(() => (geofences || []).filter(isValidCoord).map((g) => ({
    ...g,
    latitude: toNum(g.latitude),
    longitude: toNum(g.longitude),
  })), [geofences]);

  const userPositions = useMemo(
    () => (showUserMarkers ? userMarkerPositions(validGeofences, userLocations) : []),
    [validGeofences, userLocations, showUserMarkers]
  );

  const eventPositions = useMemo(() => {
    return (highlightedEvents || [])
      .map((ev) => {
        const lat = toNum(ev?.latitude);
        const lon = toNum(ev?.longitude);
        if (lat == null || lon == null) return null;
        return {
          id: ev.id,
          label: ev.label,
          userId: ev.userId,
          station: ev.station,
          eventType: ev.eventType,
          color: ev.color,
          timestamp: ev.timestamp,
          timestampIso: ev.timestampIso,
          trainLabel: ev.trainLabel,
          lat,
          lon,
        };
      })
      .filter(Boolean);
  }, [highlightedEvents]);

  return (
    <div className="rounded-xl border border-slate-200 overflow-hidden bg-slate-50" style={{ height: 520 }}>
      <MapContainer
        center={CENTER}
        zoom={ZOOM}
        style={{ height: '100%', width: '100%' }}
        scrollWheelZoom
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <SetBounds
          geofences={validGeofences}
          userPositions={userPositions}
          eventPoints={eventPositions}
        />
        {validGeofences.map((g) => (
          <Circle
            key={g.id}
            center={[g.latitude, g.longitude]}
            radius={g.radiusMeters || 150}
            pathOptions={{ color: '#4f46e5', fillColor: '#818cf8', fillOpacity: 0.2, weight: 2 }}
          >
            <Popup>
              <StationPopup g={g} />
            </Popup>
          </Circle>
        ))}
        {userPositions.map((p) => (
          <CircleMarker
            key={p.userId}
            center={[p.lat, p.lon]}
            radius={8}
            pathOptions={{ color: '#15803d', fillColor: '#22c55e', fillOpacity: 0.85, weight: 2 }}
          >
            <Popup>
              <div className="text-left text-xs min-w-[140px]">
                <p className="font-semibold text-slate-800">{p.userId}</p>
                <p className="text-slate-600 mt-0.5">Station: {p.station || '—'}</p>
                {p.lastEvent && <p className="text-slate-500 mt-1">Last event: {p.lastEvent}</p>}
              </div>
            </Popup>
          </CircleMarker>
        ))}
        {eventPositions.map((p) => (
          <CircleMarker
            key={`segment-event-${p.id}`}
            center={[p.lat, p.lon]}
            radius={10}
            pathOptions={{ color: p.color || '#334155', fillColor: p.color || '#334155', fillOpacity: 0.95, weight: 2 }}
          >
            <Popup>
              <div className="text-left text-xs min-w-[190px]">
                <p className="font-semibold text-slate-800">{p.label}</p>
                <p className="text-slate-600 mt-0.5">User: {p.userId || '—'}</p>
                <p className="text-slate-600">Station: {p.station || '—'}</p>
                <p className="text-slate-600">Event: {p.eventType || '—'}</p>
                <p className="text-slate-600">Time: {p.timestamp || '—'}</p>
                {p.timestampIso && (
                  <p className="text-[10px] text-slate-500 font-mono mt-0.5 break-all">{String(p.timestampIso)}</p>
                )}
                {p.trainLabel && <p className="text-slate-600">Train: {p.trainLabel}</p>}
              </div>
            </Popup>
          </CircleMarker>
        ))}
      </MapContainer>
    </div>
  );
}

export default AdminGeofenceMap;

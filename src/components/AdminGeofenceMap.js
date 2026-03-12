import React, { useEffect, useMemo } from 'react';
import { MapContainer, TileLayer, Circle, Popup, useMap } from 'react-leaflet';
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

function SetBounds({ geofences }) {
  const map = useMap();
  const points = useMemo(() =>
    (geofences || []).filter(isValidCoord).map((g) => [g.latitude, g.longitude]),
    [geofences]
  );
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

function AdminGeofenceMap({ geofences = [] }) {
  const validGeofences = useMemo(() => (geofences || []).filter(isValidCoord).map((g) => ({
    ...g,
    latitude: toNum(g.latitude),
    longitude: toNum(g.longitude),
  })), [geofences]);

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
        <SetBounds geofences={validGeofences} />
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
      </MapContainer>
    </div>
  );
}

export default AdminGeofenceMap;

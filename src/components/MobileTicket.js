import React, { useState, useEffect } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import * as api from '../api/client';

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

/** Single mobile ticket card: route, date, seat, QR code, ticket ID, download PDF. */
function MobileTicket({ booking, expanded: initialExpanded = false }) {
  const [expanded, setExpanded] = useState(initialExpanded);
  const [qrReady, setQrReady] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const trip = booking?.trip;
  const journeyFrom = booking?.journeyFromStation || trip?.fromStation;
  const journeyTo = booking?.journeyToStation || trip?.toStation;
  const journeyLabel = journeyFrom && journeyTo ? `${journeyFrom} → ${journeyTo}` : (trip ? `${trip.fromStation} → ${trip.toStation}` : '');
  const seats = booking?.seats?.map((s) => s.seatNumber).join(', ') || '—';
  const isActive = booking?.status === 'CONFIRMED' || booking?.status === 'PAID';
  const qrValue = booking?.id ? String(booking.id) : '';

  useEffect(() => {
    if (!booking?.id || !isActive) return;
    api.getTicketQrPayload(booking.id).then(() => setQrReady(true)).catch(() => setQrReady(false));
  }, [booking?.id, isActive]);

  return (
    <div className="rounded-xl border border-slate-200 bg-white shadow-sm overflow-hidden">
      <button
        type="button"
        onClick={() => setExpanded((e) => !e)}
        className="w-full text-left p-4 flex items-center justify-between gap-4"
      >
        <div>
          <p className="font-semibold text-slate-900">
            {journeyLabel}
          </p>
          <p className="text-sm text-slate-600">
            {formatDate(trip?.departureTime)} · Seat(s) {seats}
          </p>
        </div>
        <span className="text-slate-400 text-sm">{expanded ? '▼' : '▶'}</span>
      </button>
      {expanded && (
        <div className="border-t border-slate-100 bg-slate-50/50 p-4 space-y-4">
          <div className="flex justify-center">
            {qrValue && qrReady ? (
              <QRCodeSVG value={qrValue} size={160} level="M" />
            ) : (
              <div className="w-40 h-40 flex items-center justify-center bg-slate-200 rounded text-slate-500 text-sm">
                QR unavailable
              </div>
            )}
          </div>
          <div className="grid grid-cols-2 gap-2 text-sm">
            <span className="text-slate-500">Journey</span>
            <span className="font-medium">{journeyLabel}</span>
            <span className="text-slate-500">Travel date</span>
            <span className="font-medium">{formatDate(trip?.departureTime)}</span>
            <span className="text-slate-500">Departure</span>
            <span className="font-medium">{formatDate(trip?.departureTime)}</span>
            <span className="text-slate-500">Seat(s)</span>
            <span className="font-medium">{seats}</span>
            <span className="text-slate-500">Ticket ID</span>
            <span className="font-mono font-medium">{booking?.id}</span>
          </div>
          {isActive && (
            <button
              type="button"
              onClick={async () => {
                if (!booking?.id || downloading) return;
                setDownloading(true);
                try {
                  await api.downloadTicketPdf(booking.id);
                } catch (e) {
                  console.error(e);
                  alert(e?.message || 'Download failed');
                } finally {
                  setDownloading(false);
                }
              }}
              disabled={downloading}
              className="w-full mt-2 rounded-lg bg-slate-800 text-white py-2 text-sm font-medium hover:bg-slate-700 disabled:opacity-50"
            >
              {downloading ? 'Downloading…' : 'Download ticket (PDF)'}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export default MobileTicket;

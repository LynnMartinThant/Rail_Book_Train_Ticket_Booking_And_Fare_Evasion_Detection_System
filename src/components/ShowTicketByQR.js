import React, { useState, useRef } from 'react';
import jsQR from 'jsqr';
import * as api from '../api/client';
import MobileTicket from './MobileTicket';

/**
 * Let the user show a bought ticket by entering the ticket ID or uploading a QR code image.
 * Fetches the reservation and displays it; validates that it belongs to the current user.
 */
function ShowTicketByQR({ onClose }) {
  const [ticketIdInput, setTicketIdInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [booking, setBooking] = useState(null);
  const fileInputRef = useRef(null);

  const parseReservationIdFromQr = (str) => {
    if (!str || typeof str !== 'string') return null;
    const trimmed = str.trim();
    const num = parseInt(trimmed, 10);
    if (!Number.isNaN(num) && num > 0) return num;
    try {
      const obj = JSON.parse(trimmed);
      const id = obj.reservationId ?? obj.reservation_id ?? obj.id;
      const n = typeof id === 'number' ? id : parseInt(String(id), 10);
      if (!Number.isNaN(n) && n > 0) return n;
    } catch (_) {}
    return null;
  };

  const parseReservationIdFromFilename = (name) => {
    if (!name) return null;
    const m = String(name).match(/(\d{1,12})/);
    if (!m) return null;
    const n = parseInt(m[1], 10);
    return Number.isNaN(n) || n <= 0 ? null : n;
  };

  const fetchAndShow = async (reservationId) => {
    setError(null);
    setBooking(null);
    setLoading(true);
    try {
      const data = await api.getReservation(reservationId);
      setBooking(data);
    } catch (e) {
      setError(e.message || 'Ticket not found or not yours. Check the ticket ID or QR.');
    } finally {
      setLoading(false);
    }
  };

  const handleShowById = () => {
    const id = parseReservationIdFromQr(ticketIdInput);
    if (id == null) {
      setError('Enter a valid ticket ID (number).');
      return;
    }
    fetchAndShow(id);
  };

  const handleFileChange = (e) => {
    const file = e.target?.files?.[0];
    if (!file) return;
    setError(null);
    setBooking(null);
    setLoading(true);
    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
    if (isPdf) {
      const fromName = parseReservationIdFromFilename(file.name);
      if (fromName != null) {
        fetchAndShow(fromName);
      } else {
        setError('PDF selected. Enter reservation ID manually (or include it in the PDF filename, e.g. ticket-12345.pdf).');
        setLoading(false);
      }
      e.target.value = '';
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement('canvas');
        canvas.width = img.width;
        canvas.height = img.height;
        const ctx = canvas.getContext('2d');
        if (!ctx) {
          setError('Could not read image.');
          setLoading(false);
          return;
        }
        ctx.drawImage(img, 0, 0);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const code = jsQR(imageData.data, imageData.width, imageData.height);
        if (!code || !code.data) {
          setError('No QR code found in the image. Try a clear photo of the ticket QR.');
          setLoading(false);
          return;
        }
        const id = parseReservationIdFromQr(code.data);
        if (id == null) {
          setError('QR code does not contain a valid ticket ID.');
          setLoading(false);
          return;
        }
        fetchAndShow(id);
        e.target.value = '';
      };
      img.onerror = () => {
        setError('Could not load image.');
        setLoading(false);
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  };

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold text-slate-900">Show ticket by QR</h3>
        {onClose && (
          <button type="button" onClick={onClose} className="text-slate-500 hover:text-slate-700">✕</button>
        )}
      </div>
      <p className="text-sm text-slate-600">
        Enter your ticket ID or upload a photo of your ticket QR code to display it here.
      </p>

      <div>
        <label className="block text-sm font-medium text-slate-700 mb-1">Ticket ID</label>
        <div className="flex gap-2">
          <input
            type="text"
            inputMode="numeric"
            placeholder="e.g. 12345"
            value={ticketIdInput}
            onChange={(e) => setTicketIdInput(e.target.value)}
            className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-slate-900"
          />
          <button
            type="button"
            onClick={handleShowById}
            disabled={loading}
            className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {loading ? '…' : 'Show ticket'}
          </button>
        </div>
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 mb-1">Or upload QR image / PDF ticket</label>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*,.pdf,application/pdf"
          capture="environment"
          onChange={handleFileChange}
          className="block w-full text-sm text-slate-600 file:mr-2 file:rounded file:border-0 file:bg-blue-50 file:px-3 file:py-2 file:text-blue-700"
        />
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      )}

      {booking && (
        <div className="pt-2 border-t border-slate-100">
          <p className="text-sm font-medium text-green-700 mb-2">Valid ticket</p>
          <MobileTicket booking={booking} expanded />
        </div>
      )}
    </div>
  );
}

export default ShowTicketByQR;

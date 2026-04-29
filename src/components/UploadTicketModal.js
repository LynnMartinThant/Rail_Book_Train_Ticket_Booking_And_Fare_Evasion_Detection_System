import React, { useState, useRef } from 'react';
import jsQR from 'jsqr';
import * as api from '../api/client';

/**
 * Modal for passenger to upload ticket proof when admin marked the journey as no ticket (UNPAID_TRAVEL/UNDERPAID).
 * User can enter reservation ID or upload a QR code image from their ticket.
 */
function UploadTicketModal({ segment, onClose, onSuccess }) {
  const [reservationIdInput, setReservationIdInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const fileInputRef = useRef(null);
  const [selectedEvidenceRef, setSelectedEvidenceRef] = useState('');

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

  const submitWithReservationId = async (reservationId, evidenceReference) => {
    if (!segment?.id || !reservationId) return;
    setError(null);
    setLoading(true);
    try {
      await api.uploadTicketForSegment(
        segment.id,
        reservationId,
        'Ticket proof uploaded by passenger',
        evidenceReference || selectedEvidenceRef || null
      );
      onSuccess?.();
      onClose?.();
    } catch (e) {
      setError(e?.message || 'Upload failed');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmitId = () => {
    const id = parseReservationIdFromQr(reservationIdInput);
    if (id == null) {
      setError('Enter a valid reservation/ticket ID (number).');
      return;
    }
    submitWithReservationId(id);
  };

  const handleFileChange = (e) => {
    const file = e.target?.files?.[0];
    if (!file) return;
    setSelectedEvidenceRef(file.name || '');
    setError(null);
    setLoading(true);
    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
    if (isPdf) {
      const fromName = parseReservationIdFromFilename(file.name);
      if (fromName == null) {
        setError('PDF selected. Enter reservation ID manually, then submit. Tip: include ID in filename (e.g. ticket-12345.pdf).');
        setLoading(false);
        e.target.value = '';
        return;
      }
      submitWithReservationId(fromName, file.name);
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
        submitWithReservationId(id, file.name);
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

  const route = segment ? `${segment.originStation} → ${segment.destinationStation}` : '';

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50" aria-modal="true">
      <div className="bg-white rounded-xl shadow-xl max-w-md w-full p-6">
        <h2 className="text-lg font-semibold text-slate-900 mb-1">Upload your ticket</h2>
        <p className="text-sm text-slate-600 mb-4">
          You were marked as travelling without a ticket for <strong>{route}</strong>. If you had a valid ticket, enter the ticket ID or upload a photo of the QR code.
        </p>

        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">Ticket / Reservation ID</label>
            <input
              type="text"
              inputMode="numeric"
              placeholder="e.g. 12345"
              value={reservationIdInput}
              onChange={(e) => setReservationIdInput(e.target.value)}
              className="w-full border border-slate-300 rounded-lg px-3 py-2 text-slate-900 text-sm"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 mb-1">Or upload QR image / PDF ticket</label>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*,.pdf,application/pdf"
              onChange={handleFileChange}
              className="block w-full text-sm text-slate-600 file:mr-2 file:rounded file:border-0 file:bg-blue-50 file:px-3 file:py-2 file:text-blue-700"
            />
          </div>
        </div>

        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}

        <div className="mt-4 flex gap-2">
          <button
            type="button"
            onClick={handleSubmitId}
            disabled={loading}
            className="flex-1 py-2.5 px-4 rounded-lg bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
          >
            {loading ? 'Submitting…' : 'Submit ticket'}
          </button>
          <button
            type="button"
            onClick={onClose}
            className="py-2.5 px-4 rounded-lg border-2 border-slate-300 text-slate-700 font-medium hover:bg-slate-50"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

export default UploadTicketModal;

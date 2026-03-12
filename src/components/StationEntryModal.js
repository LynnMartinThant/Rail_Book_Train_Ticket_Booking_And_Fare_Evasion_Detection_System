import React, { useState } from 'react';
import * as api from '../api/client';

/**
 * Modal shown when user has pending station-entry actions (no ticket detected at entry).
 * Options: Buy ticket, Ignore, or I have a ticket (scan QR / enter reservation ID).
 */
function StationEntryModal({ action, onClose, onRefresh, onNavigateToBooking }) {
  const [step, setStep] = useState('options'); // 'options' | 'scan'
  const [reservationIdInput, setReservationIdInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleChoice = async (choice) => {
    setError(null);
    setLoading(true);
    try {
      await api.respondToStationEntry(action.id, choice);
      if (choice === 'BUY_TICKET' && onNavigateToBooking) onNavigateToBooking();
      if (choice === 'SCAN_QR') {
        setStep('scan');
        setLoading(false);
        return;
      }
      onRefresh();
      onClose();
    } catch (e) {
      setError(e.message);
    } finally {
      if (step === 'options' && choice !== 'SCAN_QR') setLoading(false);
    }
  };

  const handleValidateQr = async () => {
    const rid = reservationIdInput.trim();
    const num = parseInt(rid, 10);
    if (!rid || Number.isNaN(num) || num <= 0) {
      setError('Enter a valid reservation ID (number).');
      return;
    }
    setError(null);
    setLoading(true);
    try {
      await api.validateTicketQr(action.id, num);
      onRefresh();
      onClose();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50" aria-modal="true">
      <div className="bg-white rounded-xl shadow-xl max-w-md w-full p-6">
        <h2 className="text-lg font-semibold text-slate-900 mb-1">Travel detected without ticket</h2>
        <p className="text-sm text-slate-600 mb-4">
          Please purchase a valid ticket to avoid a penalty. You entered <strong>{action.stationName}</strong>.
        </p>

        {step === 'options' && (
          <div className="space-y-3">
            <button
              type="button"
              onClick={() => handleChoice('BUY_TICKET')}
              disabled={loading}
              className="w-full py-3 px-4 rounded-lg bg-blue-600 text-white font-semibold hover:bg-blue-700 disabled:opacity-50"
            >
              Buy ticket now
            </button>
            <button
              type="button"
              onClick={() => handleChoice('IGNORE')}
              disabled={loading}
              className="w-full py-2.5 px-4 rounded-lg border-2 border-slate-300 text-slate-700 font-medium hover:bg-slate-50 disabled:opacity-50"
            >
              Dismiss
            </button>
            <button
              type="button"
              onClick={() => handleChoice('SCAN_QR')}
              disabled={loading}
              className="w-full py-2.5 px-4 rounded-lg border-2 border-slate-300 text-slate-700 font-medium hover:bg-slate-50 disabled:opacity-50"
            >
              I have a ticket (scan QR)
            </button>
          </div>
        )}

        {step === 'scan' && (
          <div className="space-y-3">
            <p className="text-sm text-gray-600">
              Scan the QR code on your ticket, or enter the reservation ID if you have it.
            </p>
            <input
              type="text"
              inputMode="numeric"
              placeholder="Reservation ID"
              value={reservationIdInput}
              onChange={(e) => setReservationIdInput(e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm"
            />
            <div className="flex gap-2">
              <button
                type="button"
                onClick={handleValidateQr}
                disabled={loading}
                className="flex-1 py-2.5 px-4 rounded-lg bg-blue-600 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
              >
                Validate
              </button>
              <button
                type="button"
                onClick={() => { setStep('options'); setError(null); setReservationIdInput(''); }}
                className="py-2.5 px-4 rounded-lg border-2 border-slate-300 text-slate-700 font-medium hover:bg-slate-50"
              >
                Back
              </button>
            </div>
          </div>
        )}

        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
        <button
          type="button"
          onClick={() => { onClose(); onRefresh(); }}
          className="mt-4 w-full text-sm text-gray-500 hover:text-gray-700"
        >
          Close
        </button>
      </div>
    </div>
  );
}

export default StationEntryModal;

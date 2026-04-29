import React, { useState, useEffect, useRef, useCallback } from 'react';
import * as api from '../api/client';

function formatCountdown(secondsLeft) {
  if (secondsLeft <= 0) return '0:00';
  const m = Math.floor(secondsLeft / 60);
  const s = Math.floor(secondsLeft % 60);
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function Payment({
  reservations,
  loading,
  onPayment,
  onBack,
  onCancelReservation,
  onExpired,
  onGatewaySuccess,
}) {
  const [secondsLeft, setSecondsLeft] = useState(null);
  const [gatewayError, setGatewayError] = useState(null);
  const [clientSecret, setClientSecret] = useState(null);
  const [gatewayLoading, setGatewayLoading] = useState(false);
  const expiredFired = useRef(false);
  const total = reservations?.reduce((sum, r) => sum + Number(r.amount || 0), 0) || 0;
  const seats = reservations?.flatMap((r) => r.seats || []).map((s) => s.seatNumber).join(', ') || '';

  const expiresAt = reservations?.[0]?.expiresAt;

  useEffect(() => {
    if (!expiresAt) return;
    expiredFired.current = false;
    const end = new Date(expiresAt).getTime();
    const tick = () => {
      const left = Math.max(0, Math.floor((end - Date.now()) / 1000));
      setSecondsLeft(left);
      if (left <= 0 && onExpired && !expiredFired.current) {
        expiredFired.current = true;
        onExpired();
      }
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [expiresAt, onExpired]);

  const pollReservationStatus = useCallback(
    async (reservationId) => {
      for (let i = 0; i < 30; i++) {
        await new Promise((r) => setTimeout(r, 1500));
        try {
          const list = await api.getMyBookings();
          const r = list?.find((b) => b.id === reservationId);
          if (r && (r.status === 'PAID' || r.status === 'CONFIRMED')) {
            if (onGatewaySuccess) onGatewaySuccess();
            return;
          }
        } catch (_) {}
      }
    },
    [onGatewaySuccess]
  );

  const handlePayWithGateway = async () => {
    if (!reservations?.length) return;
    setGatewayError(null);
    setGatewayLoading(true);
    try {
      const firstId = reservations[0].id;
      const result = await api.createPaymentIntent(firstId, 'STRIPE');
      setClientSecret(result.clientSecret);
      if (result.paymentIntentId) {
        pollReservationStatus(firstId);
      }
    } catch (e) {
      setGatewayError(e.message);
    } finally {
      setGatewayLoading(false);
    }
  };

  if (!reservations?.length) return null;

  const r = reservations[0];
  const trip = r?.trip;
  const journeyFrom = r?.journeyFromStation || trip?.fromStation;
  const journeyTo = r?.journeyToStation || trip?.toStation;
  const expired = secondsLeft !== null && secondsLeft <= 0;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <button type="button" onClick={onBack} className="text-gray-600 hover:text-black">
          ← Back
        </button>
        <h2 className="text-lg font-semibold text-gray-900">Payment</h2>
      </div>

      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <p className="text-sm text-gray-600">
          {journeyFrom && journeyTo ? `${journeyFrom} → ${journeyTo}` : (trip ? `${trip.fromStation} → ${trip.toStation}` : '')}
        </p>
        <p className="text-gray-900 font-medium">Seats: {seats}</p>
        <p className="mt-2 text-xl font-semibold text-black">Total: £{total.toFixed(2)}</p>
        <p className="mt-2 text-sm text-gray-600">
          {expired ? (
            <span className="text-red-600">
              Trip considered done. Seat available for others now. Payment no longer possible.
            </span>
          ) : secondsLeft !== null ? (
            <>
              Reservation expires in: <span className="font-mono text-black">{formatCountdown(secondsLeft)}</span>
            </>
          ) : (
            'Reservation timer loading…'
          )}
        </p>
        <p className="mt-1 text-xs text-slate-500">
          If you don&apos;t pay in time, your seat will be released for others (enterprise: reservation timeout).
        </p>
      </div>

      <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm space-y-4">
        <p className="text-sm font-medium text-gray-700">Choose payment method</p>
        {gatewayError && (
          <p className="text-sm text-red-600" role="alert">
            {gatewayError}
          </p>
        )}
        {clientSecret && (
          <div className="rounded-lg border border-green-200 bg-green-50 p-4 text-sm text-green-800">
            <p className="font-medium">Payment session created</p>
            <p className="mt-1">
              Status: PAYMENT_PROCESSING. Complete payment with your card or Apple Pay in the gateway. The backend
              verifies payment via webhook and will set your ticket to PAID/CONFIRMED. Refresh or wait for the status
              to update.
            </p>
            <p className="mt-2 text-xs">
              To test: configure Stripe webhook (e.g. stripe listen --forward-to localhost:8080/webhooks/stripe) and
              use Stripe test card 4242 4242 4242 4242, or trigger the webhook when payment succeeds.
            </p>
          </div>
        )}
        <div className="flex flex-wrap gap-3">
          <button
            type="button"
            onClick={handlePayWithGateway}
            disabled={loading || gatewayLoading || expired}
            className="rounded-lg bg-indigo-600 px-4 py-2 font-medium text-white disabled:opacity-50 hover:bg-indigo-700"
          >
            {gatewayLoading ? 'Creating…' : 'Visa / Apple Pay'}
          </button>
          <button
            type="button"
            onClick={() => onPayment('')}
            disabled={loading || expired}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
          >
            Test reference (demo)
          </button>
        </div>
      </div>

      <div className="flex flex-wrap gap-4">
        <button
          type="button"
          onClick={onBack}
          className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-gray-700 hover:bg-gray-50"
        >
          Back (release seats)
        </button>
        {onCancelReservation && (
          <button
            type="button"
            onClick={onCancelReservation}
            className="rounded-lg border border-red-300 bg-white px-4 py-2 text-red-700 hover:bg-red-50"
          >
            Cancel reservation
          </button>
        )}
      </div>
    </div>
  );
}

export default Payment;

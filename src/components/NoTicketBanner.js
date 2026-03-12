import React from 'react';

/**
 * Persistent banner when travel is detected but no valid ticket (spec 7.2).
 * Neutral copy: "No ticket detected for this journey."
 */
function NoTicketBanner({ onBuyTicket, compact }) {
  if (compact) {
    return (
      <div className="rounded-lg border border-orange-300 bg-orange-50 px-4 py-3 flex items-center justify-between gap-3">
        <p className="text-sm text-orange-900 font-medium">No valid ticket detected. Buy a ticket before arrival to avoid penalty.</p>
        <button
          type="button"
          onClick={onBuyTicket}
          className="shrink-0 rounded-lg bg-orange-600 px-4 py-2 text-sm font-semibold text-white hover:bg-orange-700"
        >
          Buy ticket
        </button>
      </div>
    );
  }
  return (
    <div className="rounded-xl border-2 border-orange-400 bg-orange-50 p-4 space-y-3">
      <p className="text-base font-semibold text-orange-900">No valid ticket detected</p>
      <p className="text-sm text-orange-800">Buy a ticket before arrival to avoid penalty.</p>
      <button
        type="button"
        onClick={onBuyTicket}
        className="w-full rounded-lg bg-orange-600 py-3 text-base font-semibold text-white hover:bg-orange-700"
      >
        Buy ticket
      </button>
    </div>
  );
}

export default NoTicketBanner;

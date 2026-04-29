import React from 'react';

/** Spec: Green = valid, Red = no ticket / pending resolution, Yellow = pending. */
function TicketStatusBadge({ status }) {
  const config = {
    valid: { label: 'Valid ticket', className: 'bg-green-100 text-green-800 border-green-300' },
    no_ticket: { label: 'No ticket detected', className: 'bg-red-100 text-red-800 border-red-300' },
    pending_resolution: { label: 'Resolve within 1 hour', className: 'bg-orange-100 text-orange-800 border-orange-300' },
    pending_review: { label: 'Pending review', className: 'bg-amber-100 text-amber-900 border-amber-300' },
    pending: { label: 'Verification pending', className: 'bg-amber-100 text-amber-800 border-amber-300' },
  };
  const c = config[status] || config.pending;
  return (
    <span className={`inline-flex items-center rounded-full border px-3 py-1 text-sm font-medium ${c.className}`}>
      {c.label}
    </span>
  );
}

export default TicketStatusBadge;

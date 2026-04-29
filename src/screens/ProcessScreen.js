import React from 'react';

function Step({ title, body }) {
  return (
    <li className="rounded-xl border border-slate-200 bg-white p-4">
      <p className="text-sm font-semibold text-slate-900">{title}</p>
      <p className="mt-1 text-sm text-slate-700">{body}</p>
    </li>
  );
}

/** Personal reference page: how RailBook processes data end-to-end. */
function ProcessScreen() {
  return (
    <div className="space-y-6 pb-24">
      <section className="rounded-xl border border-blue-200 bg-blue-50 p-4">
        <h1 className="text-xl font-semibold text-slate-900">How The System Processes Data</h1>
        <p className="mt-2 text-sm text-slate-700">
          This page explains the live processing path in your app from movement signals to fare decision,
          and what happens when confidence is high vs low.
        </p>
      </section>

      <section>
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-600 mb-2">Pipeline stages</h2>
        <ol className="space-y-2">
          <Step
            title="1) Raw movement input"
            body="The app reports location. Backend converts location into station-level geofence entry/exit events."
          />
          <Step
            title="2) Event stream + validation"
            body="Events flow through pipeline consumers. Debounce, ordering and station checks prevent noisy duplicates."
          />
          <Step
            title="3) Journey reconstruction"
            body="The system builds a Trip Segment from station exit to station entry, with origin, destination and times."
          />
          <Step
            title="4) Ticket coverage check"
            body="Segment is matched against confirmed/paid entitlement in RailBook. Coverage = full, partial, or none."
          />
          <Step
            title="5) Confidence scoring"
            body="Confidence combines geofence quality, temporal consistency, movement completeness, route alignment and entitlement support."
          />
          <Step
            title="6) Decision engine"
            body="Decision table outputs PAID, UNDERPAID, PENDING_REVIEW, PENDING_RESOLUTION, or UNPAID_TRAVEL depending on evidence strength and coverage."
          />
          <Step
            title="7) Enforcement and review"
            body="High confidence can allow automatic enforcement paths. Low confidence is gated to review/no automatic penalty."
          />
          <Step
            title="8) Explainability + admin evidence"
            body="Each segment stores explanation JSON (rules, inputs, confidence breakdown, and transitions) for audit and dispute review."
          />
        </ol>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-4">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-600 mb-2">Decision behavior mapping</h2>
        <ul className="space-y-1 text-sm text-slate-700">
          <li><span className="font-medium">Valid -> Verify -> Valid:</span> full ticket coverage + strong confidence leads to paid/cleared outcome.</li>
          <li><span className="font-medium">Violation -> Review -> User action:</span> no/partial coverage creates resolution or review state; user can submit proof for recomputation.</li>
          <li><span className="font-medium">Uncertain -> Low confidence -> No enforcement:</span> confidence gating keeps automated penalties suppressed and routes to review.</li>
        </ul>
      </section>
    </div>
  );
}

export default ProcessScreen;

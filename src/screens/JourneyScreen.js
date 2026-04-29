import React, { useState, useEffect } from 'react';
import TicketStatusBadge from '../components/TicketStatusBadge';
import UploadTicketModal from '../components/UploadTicketModal';

function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

function fareStatusToTicketStatus(fareStatus) {
  if (fareStatus === 'PAID') return 'valid';
  if (fareStatus === 'PENDING_REVIEW') return 'pending_review';
  if (fareStatus === 'PENDING_RESOLUTION') return 'pending_resolution';
  if (fareStatus === 'UNPAID_TRAVEL' || fareStatus === 'UNDERPAID') return 'no_ticket';
  return 'pending';
}

/** Turn backend explanation JSON into short labelled lines for passengers. */
function explanationSummaryLines(explanation) {
  if (!explanation || typeof explanation !== 'object') return [];
  const lines = [];
  if (explanation.pipelineSource) lines.push({ label: 'Checked by', value: String(explanation.pipelineSource) });
  if (explanation.policyName) lines.push({ label: 'Policy', value: String(explanation.policyName) });
  const fd = explanation.fareDecision;
  if (fd && typeof fd === 'object') {
    if (fd.policyName) lines.push({ label: 'Fare policy', value: String(fd.policyName) });
    if (fd.ruleCode) lines.push({ label: 'Rule', value: String(fd.ruleCode) });
    if (fd.decisionReason) lines.push({ label: 'Decision', value: String(fd.decisionReason) });
  }
  if (explanation.decisionReason && !lines.some((l) => l.label === 'Decision')) {
    lines.push({ label: 'Decision', value: String(explanation.decisionReason) });
  }
  if (explanation.confidenceScore != null) {
    lines.push({ label: 'Confidence score', value: String(explanation.confidenceScore) });
  }
  if (explanation.reviewRequired != null) {
    lines.push({ label: 'Manual review', value: explanation.reviewRequired ? 'May be required' : 'Not required' });
  }
  const recon = explanation.reconstructionConfidence;
  if (recon && typeof recon === 'object') {
    if (recon.decisionSummary) lines.push({ label: 'Journey detection', value: String(recon.decisionSummary) });
    else if (recon.policyName) lines.push({ label: 'Journey policy', value: String(recon.policyName) });
  }
  const dj = explanation.detectedJourney;
  if (dj && typeof dj === 'object' && (dj.originStation || dj.destinationStation)) {
    lines.push({
      label: 'Detected route',
      value: `${dj.originStation || '?'} → ${dj.destinationStation || '?'}`,
    });
  }
  return lines;
}

function JourneyScreen({ segments, onBuyTicket, onRefreshSegments }) {
  const [segmentToUpload, setSegmentToUpload] = useState(null);
  const [explainOpen, setExplainOpen] = useState(false);
  const currentOrLatest = segments && segments[0];
  useEffect(() => {
    setExplainOpen(false);
  }, [currentOrLatest?.id]);
  const hasUnpaid = segments && segments.some((s) => s.fareStatus === 'UNPAID_TRAVEL' || s.fareStatus === 'UNDERPAID' || s.fareStatus === 'PENDING_RESOLUTION' || s.fareStatus === 'PENDING_REVIEW');
  const unpaidSegment = segments && segments.find((s) => s.fareStatus === 'PENDING_RESOLUTION' || s.fareStatus === 'UNPAID_TRAVEL' || s.fareStatus === 'UNDERPAID' || s.fareStatus === 'PENDING_REVIEW');
  const isPendingResolution = unpaidSegment && unpaidSegment.fareStatus === 'PENDING_RESOLUTION';
  const isPendingReview = unpaidSegment && unpaidSegment.fareStatus === 'PENDING_REVIEW';

  return (
    <div className="space-y-6 pb-24">
      {segmentToUpload && (
        <UploadTicketModal
          segment={segmentToUpload}
          onClose={() => setSegmentToUpload(null)}
          onSuccess={() => { onRefreshSegments?.(); setSegmentToUpload(null); }}
        />
      )}
      {hasUnpaid && (
        <div className="rounded-xl border-2 border-red-300 bg-red-50 p-4">
          <p className="font-semibold text-red-900">
            {isPendingReview ? 'Journey flagged for review' : (isPendingResolution ? 'Unauthorised travel detected' : 'No ticket detected for this journey.')}
          </p>
          <p className="text-sm text-red-800 mt-1">
            {isPendingReview
              ? `We detected a journey from ${unpaidSegment.originStation} to ${unpaidSegment.destinationStation}, but confidence is not high enough for automatic enforcement. This has been flagged for review.`
              : isPendingResolution
              ? `We detected that you travelled from ${unpaidSegment.originStation} to ${unpaidSegment.destinationStation} without a valid ticket. Please resolve this within 1 hour to avoid a penalty.`
              : 'Purchase a valid ticket to avoid a penalty, or upload your ticket if you had one.'}
          </p>
          <div className="mt-3 flex flex-col gap-2">
            <button type="button" onClick={onBuyTicket} className="w-full rounded-lg bg-red-600 py-3 text-base font-semibold text-white hover:bg-red-700">
              Purchase ticket
            </button>
            <button
              type="button"
              onClick={() => setSegmentToUpload(unpaidSegment)}
              className="w-full rounded-lg border-2 border-red-400 bg-white py-3 text-base font-semibold text-red-800 hover:bg-red-50"
            >
              Upload external ticket (QR or reference)
            </button>
            {isPendingResolution && (
              <p className="text-xs text-red-700 mt-1">If you do nothing, a penalty will be issued after the 1-hour window.</p>
            )}
          </div>
        </div>
      )}
      {currentOrLatest ? (
        <section className="rounded-xl border-2 border-blue-200 bg-white p-4 space-y-4">
          <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-wide">Current / latest journey</h2>
          <p className="text-lg font-semibold text-slate-900">{currentOrLatest.originStation} to {currentOrLatest.destinationStation}</p>
          {(currentOrLatest.originPlatform || currentOrLatest.destinationPlatform) && (
            <p className="text-sm text-slate-600">
              Platform {currentOrLatest.originPlatform || '—'} → Platform {currentOrLatest.destinationPlatform || '—'}
            </p>
          )}
          <p className="text-sm text-slate-600">Departure detected: {formatDate(currentOrLatest.segmentStartTime)}</p>
          <div className="flex items-center gap-2">
            <span className="text-sm text-slate-600">Ticket verification</span>
            <TicketStatusBadge status={fareStatusToTicketStatus(currentOrLatest.fareStatus)} />
          </div>
          {explanationSummaryLines(currentOrLatest.explanation).length > 0 && (
            <div className="rounded-lg border border-slate-200 bg-slate-50/80">
              <button
                type="button"
                onClick={() => setExplainOpen((o) => !o)}
                className="flex w-full items-center justify-between px-3 py-2 text-left text-sm font-medium text-slate-800"
              >
                <span>How we decided this</span>
                <span className="text-slate-500">{explainOpen ? '▼' : '▶'}</span>
              </button>
              {explainOpen && (
                <div className="border-t border-slate-200 px-3 py-2 space-y-2 text-sm text-slate-700">
                  <p className="text-xs text-slate-500">
                    Your ticket was compared to the journey we detected from station signals. This summary comes from the same structured record our team uses for disputes.
                  </p>
                  <ul className="space-y-1.5">
                    {explanationSummaryLines(currentOrLatest.explanation).map((row) => (
                      <li key={row.label}>
                        <span className="text-slate-500">{row.label}: </span>
                        <span className="text-slate-900">{row.value}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
          {(currentOrLatest.fareStatus === 'PENDING_RESOLUTION' && currentOrLatest.resolutionDeadline) && (
            <div className="rounded-lg bg-orange-50 border border-orange-200 p-3">
              <p className="text-sm font-medium text-orange-900">Resolve by: {formatDate(currentOrLatest.resolutionDeadline)}</p>
              <div className="mt-2 flex flex-wrap gap-2">
                <button type="button" onClick={onBuyTicket} className="text-sm font-semibold text-orange-800 underline">Purchase ticket</button>
                <button type="button" onClick={() => setSegmentToUpload(currentOrLatest)} className="text-sm font-semibold text-orange-800 underline">Upload ticket</button>
              </div>
            </div>
          )}
          {currentOrLatest.penaltyAmount != null && Number(currentOrLatest.penaltyAmount) > 0 && (
            <div className="rounded-lg bg-amber-50 border border-amber-200 p-3">
              <p className="text-sm font-medium text-amber-900">Penalty charge: £{Number(currentOrLatest.penaltyAmount).toFixed(2)}</p>
              <div className="mt-2 flex flex-wrap gap-2">
                <button type="button" onClick={onBuyTicket} className="text-sm font-semibold text-amber-800 underline">Pay now</button>
                <button type="button" onClick={() => setSegmentToUpload(currentOrLatest)} className="text-sm font-semibold text-amber-800 underline">I had a ticket – upload proof</button>
              </div>
            </div>
          )}
        </section>
      ) : (
        <section className="rounded-xl border border-slate-200 bg-slate-50 p-6 text-center">
          <p className="text-slate-600">No journey detected yet.</p>
          <p className="text-sm text-slate-500 mt-1">Travel between stations to see your journey here.</p>
        </section>
      )}
      <section>
        <h2 className="text-lg font-semibold text-slate-900 mb-3">Detection evidence</h2>
        <p className="text-sm text-slate-600 mb-3">Geofence entry times and ticket verification for inspection or dispute resolution.</p>
        {currentOrLatest && (
          <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm space-y-1 mb-4">
            <p><span className="text-slate-500">Origin:</span> {currentOrLatest.originStation}{currentOrLatest.originPlatform ? ` (Platform ${currentOrLatest.originPlatform})` : ''} — {formatDate(currentOrLatest.segmentStartTime)}</p>
            <p><span className="text-slate-500">Destination:</span> {currentOrLatest.destinationStation}{currentOrLatest.destinationPlatform ? ` (Platform ${currentOrLatest.destinationPlatform})` : ''} — {formatDate(currentOrLatest.segmentEndTime)}</p>
            <p><span className="text-slate-500">Ticket verification:</span> {currentOrLatest.fareStatus}</p>
          </div>
        )}
      </section>
      <section>
        <h2 className="text-lg font-semibold text-slate-900 mb-3">Journey history</h2>
        {!segments || segments.length === 0 ? (
          <p className="text-slate-600 text-sm">No journey history.</p>
        ) : (
          <ul className="space-y-3">
            {segments.map((s) => (
              <li key={s.id} className="rounded-lg border border-slate-200 bg-white p-3">
                <p className="font-medium text-slate-900">{s.originStation} to {s.destinationStation}</p>
                {(s.originPlatform || s.destinationPlatform) && (
                  <p className="text-xs text-slate-500">Platform {s.originPlatform || '—'} → {s.destinationPlatform || '—'}</p>
                )}
                <p className="text-xs text-slate-500">{formatDate(s.segmentStartTime)} – {formatDate(s.segmentEndTime)}</p>
                <TicketStatusBadge status={fareStatusToTicketStatus(s.fareStatus)} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

export default JourneyScreen;

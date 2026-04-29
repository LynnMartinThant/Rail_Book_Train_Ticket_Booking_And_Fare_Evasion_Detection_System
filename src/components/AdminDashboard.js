import React, { useCallback, useEffect, useMemo, useState } from 'react';
import * as api from '../api/client';
import AdminGeofenceMap from './AdminGeofenceMap';

function formatDate(iso) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function normStation(s) {
  return String(s ?? '').trim().toLowerCase();
}

/** Match segment station label to a geofence row (name / stationName). */
function geofenceMatchesStationName(g, stationLabel) {
  const want = normStation(stationLabel);
  if (!want) return false;
  const sn = normStation(g?.stationName);
  const nm = normStation(g?.name);
  return sn === want
    || (sn && (want.includes(sn) || sn.includes(want)))
    || (nm && (want.includes(nm) || nm.includes(want)));
}

function eventMatchesStation(ev, stationLabel) {
  return geofenceMatchesStationName(ev?.geofence, stationLabel);
}

function pickDefaultDecisionSegment(segments) {
  if (!Array.isArray(segments) || segments.length === 0) return null;
  const bySimUnpaid = segments.find((s) => s.passengerId === 'sim-unpaid');
  if (bySimUnpaid) return bySimUnpaid;
  const nonPaid = segments.find((s) => s.fareStatus && s.fareStatus !== 'PAID');
  if (nonPaid) return nonPaid;
  return segments[0];
}

function decisionCardTitle(fareStatus) {
  if (!fareStatus) return 'UNKNOWN';
  if (fareStatus === 'UNPAID_TRAVEL') return 'UNPAID';
  if (fareStatus === 'PENDING_RESOLUTION') return 'PENDING RESOLUTION';
  if (fareStatus === 'PENDING_REVIEW') return 'REVIEW';
  return fareStatus.replace(/_/g, ' ');
}

function decisionCardReason(segment) {
  const ex = segment?.explanation;
  const fare = ex?.fareDecision?.decisionSummary;
  if (fare && String(fare).trim()) return String(fare).trim();
  if (segment?.qualityImpact && String(segment.qualityImpact).trim()) return String(segment.qualityImpact).trim();
  if (Array.isArray(segment?.confidenceReasons) && segment.confidenceReasons.length > 0) {
    return segment.confidenceReasons.join('; ');
  }
  if (segment?.fareStatus === 'UNPAID_TRAVEL') return 'Travel without valid ticket or penalty applied.';
  if (segment?.fareStatus === 'UNDERPAID') return 'Travel beyond ticket coverage (additional fare due).';
  if (segment?.fareStatus === 'PENDING_RESOLUTION') return 'No valid ticket detected; resolve within the window.';
  if (segment?.fareStatus === 'PENDING_REVIEW') return 'Confidence or policy requires manual review.';
  if (segment?.fareStatus === 'PAID') return 'Ticket covers reconstructed journey segment.';
  return 'See segment explanation for policy details.';
}

function formatConfidenceLine(segment) {
  const score = segment?.confidenceScore;
  const band = segment?.confidenceBand || '—';
  if (score == null || score === '') return `— (${band})`;
  const n = Number(score);
  const pct = Number.isFinite(n) ? `${Math.round(n)}%` : `${score}%`;
  return `${pct} (${band})`;
}

function safeObj(v) {
  return v && typeof v === 'object' && !Array.isArray(v) ? v : null;
}

function fmtMoney(n) {
  if (n == null || n === '') return '—';
  const x = Number(n);
  return Number.isFinite(x) ? `£${x.toFixed(2)}` : String(n);
}

function decisionCardCalculationLines(segment) {
  const ex = segment?.explanation;
  if (!ex || typeof ex !== 'object') {
    return [
      'No structured explanation on this segment yet. Refresh after journey confirmation / fare policy merge, or replay a demo scenario.',
    ];
  }
  const lines = [];

  const dj = safeObj(ex.detectedJourney);
  if (dj && (dj.originStation || dj.destinationStation)) {
    lines.push(
      `Reconstructed journey: ${dj.originStation || '—'} → ${dj.destinationStation || '—'} (${dj.segmentStartTime || '—'} → ${dj.segmentEndTime || '—'}).`,
    );
  }

  const moneyBits = [];
  if (segment?.paidFare != null && segment.paidFare !== '') moneyBits.push(`ticket / paid component ${fmtMoney(segment.paidFare)}`);
  if (segment?.additionalFare != null && segment.additionalFare !== '') moneyBits.push(`additional fare ${fmtMoney(segment.additionalFare)}`);
  if (segment?.penaltyAmount != null && segment.penaltyAmount !== '') moneyBits.push(`penalty ${fmtMoney(segment.penaltyAmount)}`);
  if (moneyBits.length) lines.push(`Amounts on segment: ${moneyBits.join('; ')}.`);

  const fare = safeObj(ex.fareDecision);
  if (fare) {
    const ins = safeObj(fare.inputs);
    const bits = [];
    if (fare.policyName) bits.push(String(fare.policyName));
    if (fare.ruleCode) bits.push(`code ${String(fare.ruleCode)}`);
    if (ins) {
      if (ins.referenceFareForRoute != null) bits.push(`route table fare ${fmtMoney(ins.referenceFareForRoute)}`);
      if (ins.fullRouteFare != null) bits.push(`full route fare ${fmtMoney(ins.fullRouteFare)}`);
      if (ins.additionalFare != null) bits.push(`additional ${fmtMoney(ins.additionalFare)}`);
      if (ins.entitledRouteFareFromTable != null) bits.push(`entitled (table) ${fmtMoney(ins.entitledRouteFareFromTable)}`);
      if (ins.provisionalPenaltyIfUnpaid != null) bits.push(`provisional penalty ${fmtMoney(ins.provisionalPenaltyIfUnpaid)}`);
      if (ins.confirmedReservationCount != null) bits.push(`${ins.confirmedReservationCount} entitlement(s) checked`);
    }
    const summary = fare.decisionSummary ? ` ${String(fare.decisionSummary)}` : '';
    lines.push(`Fare step: ${bits.join(' · ')}.${summary}`);
  }

  const cc = safeObj(ex.computedConfidence);
  if (cc) {
    const br = safeObj(cc.breakdown);
    const scoreStr = cc.score != null && cc.score !== '' ? `${Math.round(Number(cc.score))}%` : '—';
    const formula = cc.formula ? ` ${String(cc.formula)}` : '';
    lines.push(`Confidence score: ${scoreStr} (${cc.band || '—'}).${formula}`);
    if (br) {
      lines.push(
        `Weighted inputs: geofence ${br.geofenceScore ?? '—'}, temporal ${br.temporalScore ?? '—'}, movement ${br.movementCompletenessScore ?? '—'}, route ${br.routeAlignmentScore ?? '—'}, entitlement ${br.entitlementSupportScore ?? '—'}, penalty term ${br.penaltyScore ?? '—'}.`,
      );
      const reasons = Array.isArray(br.reasons) ? br.reasons.filter(Boolean) : [];
      if (reasons.length) {
        lines.push(`Reasons: ${reasons.slice(0, 5).join('; ')}${reasons.length > 5 ? ' …' : ''}`);
      }
    }
  }

  const co = safeObj(ex.confidenceOutcome);
  if (co) {
    const pun = co.punitiveAllowed === false ? ' Punitive enforcement not allowed.' : '';
    lines.push(`Segment decision: ${co.ruleCode || '—'} — ${co.decisionSummary || '—'}.${pun}`);
  }

  const enf = safeObj(ex.enforcement);
  if (enf) {
    lines.push(`Enforcement step: ${enf.ruleCode || '—'} — ${enf.decisionSummary || '—'}.`);
    const gate = safeObj(enf.confidenceGate);
    if (gate && (gate.autoPenaltyThresholdPercent != null || gate.passed != null)) {
      lines.push(
        `Auto-penalty gate: threshold ${gate.autoPenaltyThresholdPercent ?? '—'}%, passed ${gate.passed === true ? 'yes' : gate.passed === false ? 'no' : '—'}.`,
      );
    }
  }

  const r5 = safeObj(ex.rule5_confidenceEnforcement);
  if (r5 && r5.summary && !enf) {
    lines.push(`Confidence enforcement: ${String(r5.summary)}`);
  }

  const uk = safeObj(ex.ukFareRules);
  if (uk) {
    Object.keys(uk).forEach((key) => {
      const block = safeObj(uk[key]);
      if (block && block.summary) lines.push(`Fare rules (${key}): ${String(block.summary)}`);
    });
  }

  if (lines.length === 0) {
    return ['Explanation JSON is present but no recognised calculation blocks (fareDecision / computedConfidence).'];
  }
  return lines;
}

function AdminDashboard({ secret, onLogout }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [geofences, setGeofences] = useState([]);
  const [geofenceEvents, setGeofenceEvents] = useState([]);
  const [movementEvents, setMovementEvents] = useState([]);
  const [segments, setSegments] = useState([]);
  /** Admin fare-case feed (includes PENDING_REVIEW); may contain segments not in the latest listAll page. */
  const [fareCaseSegments, setFareCaseSegments] = useState([]);
  const [tripsById, setTripsById] = useState({});
  const [disputes, setDisputes] = useState([]);
  const [disputeStatusFilter, setDisputeStatusFilter] = useState('OPEN');
  const [reviewActionId, setReviewActionId] = useState(null);
  const [pendingCaseStateFilter, setPendingCaseStateFilter] = useState('ALL');
  const [pendingStateActionId, setPendingStateActionId] = useState(null);
  const [simUserId, setSimUserId] = useState('4');
  const [simOriginId, setSimOriginId] = useState('');
  const [simDestId, setSimDestId] = useState('');
  const [simLoading, setSimLoading] = useState(false);
  const [simOk, setSimOk] = useState(null);
  const [decisionCardSegmentId, setDecisionCardSegmentId] = useState(null);
  const [calcLoading, setCalcLoading] = useState(false);
  const [calcAt, setCalcAt] = useState(null);

  const loadCore = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [g, ge, me, ts, cases, d, trips] = await Promise.all([
        api.getAdminGeofences(secret),
        api.getAdminGeofenceEvents(secret, 200),
        api.getAdminMovementEvents(secret, { limit: 40 }),
        api.getAdminTripSegments(secret, null, 100),
        api.getAdminFareEvasionCases(secret, 200).catch(() => []),
        api.getAdminDisputes(secret, disputeStatusFilter || undefined).catch(() => []),
        api.getTrips().catch(() => []),
      ]);
      setGeofences(Array.isArray(g) ? g : []);
      setGeofenceEvents(Array.isArray(ge) ? ge : []);
      setMovementEvents(Array.isArray(me) ? me : []);
      setSegments(Array.isArray(ts) ? ts : []);
      setFareCaseSegments(Array.isArray(cases) ? cases : []);
      setDisputes(Array.isArray(d) ? d : []);
      const tripMap = {};
      (Array.isArray(trips) ? trips : []).forEach((t) => {
        if (t?.id != null) tripMap[String(t.id)] = t;
      });
      setTripsById(tripMap);
    } catch (e) {
      setError(e.message || 'Failed to load demo core data');
    } finally {
      setLoading(false);
    }
  }, [secret, disputeStatusFilter]);

  useEffect(() => {
    if (secret) loadCore();
  }, [secret, loadCore]);

  const allSegmentsForPick = useMemo(() => {
    const map = new Map();
    (fareCaseSegments || []).forEach((s) => {
      if (s?.id != null) map.set(s.id, s);
    });
    (segments || []).forEach((s) => {
      if (s?.id != null) map.set(s.id, s);
    });
    return [...map.values()].sort((a, b) => {
      const ta = a?.createdAt ? Date.parse(a.createdAt) : 0;
      const tb = b?.createdAt ? Date.parse(b.createdAt) : 0;
      return tb - ta;
    });
  }, [segments, fareCaseSegments]);

  const pendingReviewCases = useMemo(() => {
    const base = (fareCaseSegments || []).filter((s) => s?.fareStatus === 'PENDING_REVIEW');
    if (pendingCaseStateFilter === 'ALL') return base;
    return base.filter((s) => (s?.segmentState || '') === pendingCaseStateFilter);
  }, [fareCaseSegments, pendingCaseStateFilter]);

  useEffect(() => {
    if (!allSegmentsForPick.length) return;
    const stillThere = decisionCardSegmentId != null && allSegmentsForPick.some((s) => s.id === decisionCardSegmentId);
    if (!stillThere) {
      const d = pickDefaultDecisionSegment(allSegmentsForPick);
      if (d) setDecisionCardSegmentId(d.id);
    }
  }, [allSegmentsForPick, decisionCardSegmentId]);

  const decisionSegment = useMemo(() => {
    if (!allSegmentsForPick.length) return null;
    if (decisionCardSegmentId != null) {
      const pinned = allSegmentsForPick.find((s) => s.id === decisionCardSegmentId);
      if (pinned) return pinned;
    }
    return pickDefaultDecisionSegment(allSegmentsForPick);
  }, [allSegmentsForPick, decisionCardSegmentId]);

  const handleSimulateJourney = async (e) => {
    e.preventDefault();
    if (!simUserId || !simOriginId || !simDestId) return;
    setSimLoading(true);
    setSimOk(null);
    setError(null);
    try {
      const now = new Date();
      const entry = now.toISOString();
      const after = new Date(now.getTime() + 4 * 60 * 1000).toISOString();
      const data = await api.simulateJourney(secret, simUserId.trim(), Number(simOriginId), Number(simDestId), entry, after);
      setSimOk(data?.message || 'Journey simulation recorded');
      await loadCore();
    } catch (e2) {
      setError(e2.message || 'Simulation failed');
    } finally {
      setSimLoading(false);
    }
  };

  const handleCalculateNow = async () => {
    setCalcLoading(true);
    setError(null);
    try {
      await loadCore();
      setCalcAt(new Date());
    } catch (e) {
      setError(e.message || 'Calculation refresh failed');
    } finally {
      setCalcLoading(false);
    }
  };

  const handleDisputeAction = async (disputeId, action) => {
    if (!disputeId) return;
    setReviewActionId(disputeId);
    setError(null);
    try {
      if (action === 'under-review') await api.markAdminDisputeUnderReview(secret, disputeId);
      if (action === 'accept') await api.acceptAdminDispute(secret, disputeId);
      if (action === 'reject') await api.rejectAdminDispute(secret, disputeId);
      await loadCore();
    } catch (e) {
      setError(e.message || 'Failed to update dispute');
    } finally {
      setReviewActionId(null);
    }
  };

  const handlePendingReviewStateAction = async (segmentId, nextState) => {
    if (!segmentId || !nextState) return;
    setPendingStateActionId(segmentId);
    setError(null);
    try {
      await api.updateAdminPendingReviewState(secret, segmentId, nextState);
      await loadCore();
    } catch (e) {
      setError(e.message || 'Failed to update pending review state');
    } finally {
      setPendingStateActionId(null);
    }
  };

  const isPaid = decisionSegment?.fareStatus === 'PAID';
  const isWarn = decisionSegment && ['PENDING_RESOLUTION', 'PENDING_REVIEW', 'UNDERPAID'].includes(decisionSegment.fareStatus);
  const isBad = decisionSegment && decisionSegment.fareStatus === 'UNPAID_TRAVEL';
  const cardBorder = isPaid
    ? 'border-emerald-300 bg-emerald-50/90'
    : isBad
      ? 'border-red-400 bg-red-50/90'
      : isWarn
        ? 'border-amber-400 bg-amber-50/90'
        : 'border-slate-300 bg-slate-50';

  const origin = decisionSegment?.origin || decisionSegment?.originStation || '—';
  const dest = decisionSegment?.destination || decisionSegment?.destinationStation || '—';
  const matchedTrip = decisionSegment?.matchedTripId != null ? tripsById[String(decisionSegment.matchedTripId)] : null;
  const trainLabel = matchedTrip?.trainName
    ? `${matchedTrip.trainName} (${matchedTrip.fromStation} → ${matchedTrip.toStation})`
    : (decisionSegment?.matchedTripId != null ? `Trip #${decisionSegment.matchedTripId}` : null);

  const segmentMapGeofences = useMemo(() => {
    if (!decisionSegment || !Array.isArray(geofences) || geofences.length === 0) return geofences;
    const o = decisionSegment.originStation || decisionSegment.origin;
    const d = decisionSegment.destinationStation || decisionSegment.destination;
    const onRoute = geofences.filter(
      (g) => geofenceMatchesStationName(g, o) || geofenceMatchesStationName(g, d),
    );
    return onRoute.length > 0 ? onRoute : geofences;
  }, [decisionSegment, geofences]);

  const highlightedMapEvents = useMemo(() => {
    if (!decisionSegment) return [];
    const userId = decisionSegment.passengerId;
    const oSt = decisionSegment.originStation || decisionSegment.origin;
    const dSt = decisionSegment.destinationStation || decisionSegment.destination;
    const startMs = decisionSegment.segmentStartTime ? Date.parse(decisionSegment.segmentStartTime) : NaN;
    const endMs = decisionSegment.segmentEndTime ? Date.parse(decisionSegment.segmentEndTime) : NaN;
    const padMs = 5 * 60 * 1000;
    const windowStart = Number.isFinite(startMs) ? startMs - padMs : Number.NEGATIVE_INFINITY;
    const windowEnd = Number.isFinite(endMs) ? endMs + padMs : Number.POSITIVE_INFINITY;
    const events = (geofenceEvents || [])
      .filter((ev) => ev?.userId === userId)
      .filter((ev) => {
        const t = Date.parse(ev?.createdAt);
        if (!Number.isFinite(t)) return false;
        return t >= windowStart && t <= windowEnd;
      })
      .sort((a, b) => Date.parse(a?.createdAt || 0) - Date.parse(b?.createdAt || 0));

    const entry = events.find(
      (ev) => ev?.eventType === 'ENTERED' && ev?.geofence && eventMatchesStation(ev, oSt),
    );
    let exit = [...events].reverse().find(
      (ev) => ev?.eventType === 'EXITED' && ev?.geofence && eventMatchesStation(ev, dSt),
    );
    if (!exit) {
      exit = [...events].reverse().find(
        (ev) => ev?.eventType === 'ENTERED' && ev?.geofence && eventMatchesStation(ev, dSt),
      );
    }
    if (!exit) {
      exit = [...events].reverse().find(
        (ev) => ev?.eventType === 'EXITED' && ev?.geofence && eventMatchesStation(ev, oSt),
      );
    }

    const markers = [];
    if (entry?.geofence) {
      markers.push({
        id: `${entry.id || 'entry'}-entry`,
        label: 'Entry (origin)',
        userId,
        station: entry.geofence.stationName || entry.geofence.name,
        eventType: entry.eventType,
        timestamp: formatDate(entry.createdAt),
        timestampIso: entry.createdAt,
        trainLabel,
        latitude: entry.geofence.latitude,
        longitude: entry.geofence.longitude,
        color: '#16a34a',
      });
    }
    if (exit?.geofence && (!entry?.id || exit.id !== entry.id || exit.eventType !== entry.eventType)) {
      markers.push({
        id: `${exit.id || 'exit'}-exit`,
        label: exit.eventType === 'ENTERED' ? 'Arrival (destination)' : 'Exit',
        userId,
        station: exit.geofence.stationName || exit.geofence.name,
        eventType: exit.eventType,
        timestamp: formatDate(exit.createdAt),
        timestampIso: exit.createdAt,
        trainLabel,
        latitude: exit.geofence.latitude,
        longitude: exit.geofence.longitude,
        color: '#dc2626',
      });
    }
    return markers;
  }, [decisionSegment, geofenceEvents, trainLabel]);

  return (
    <div className="min-h-screen bg-slate-100 p-6">
      <div className="mx-auto max-w-7xl space-y-6">
        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold text-slate-900">Demo Core Dashboard</h1>
            <p className="text-sm text-slate-600">Segment-focused geofence map and journey decision explainability.</p>
          </div>
          <div className="flex gap-2">
            <button type="button" onClick={loadCore} disabled={loading} className="rounded-lg bg-slate-800 px-4 py-2 text-sm text-white disabled:opacity-50">{loading ? 'Refreshing...' : 'Refresh'}</button>
            <button type="button" onClick={onLogout} className="rounded-lg border border-slate-300 px-4 py-2 text-sm text-slate-700">Log out</button>
          </div>
        </div>

        {error && <div className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
        {simOk && <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{simOk}</div>}

        <div className="grid gap-4 md:grid-cols-4">
          <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"><p className="text-xs text-slate-500">Geofence events</p><p className="mt-1 text-2xl font-semibold">{geofenceEvents.length}</p></div>
          <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"><p className="text-xs text-slate-500">Movement events</p><p className="mt-1 text-2xl font-semibold">{movementEvents.length}</p></div>
          <div className="rounded-xl border border-amber-200 bg-amber-50/80 p-4 shadow-sm"><p className="text-xs text-amber-900/80">Pending review cases</p><p className="mt-1 text-2xl font-semibold text-amber-950">{pendingReviewCases.length}</p></div>
          <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"><p className="text-xs text-slate-500">Open disputes</p><p className="mt-1 text-2xl font-semibold">{disputes.length}</p></div>
        </div>

        <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-4 shadow-sm">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold text-amber-950">Pending review queue (fare status)</h2>
            <div className="flex items-center gap-2">
              <select
                value={pendingCaseStateFilter}
                onChange={(e) => setPendingCaseStateFilter(e.target.value)}
                className="rounded-lg border border-amber-300 bg-white px-2 py-1 text-xs"
              >
                <option value="ALL">ALL STATES</option>
                <option value="PENDING_REVIEW">PENDING_REVIEW</option>
                <option value="ESCALATED_FRAUD_REVIEW">ESCALATED_FRAUD_REVIEW</option>
                <option value="CLOSED">CLOSED</option>
              </select>
              <p className="text-xs text-amber-900/80">
                Confidence or policy sent these segments to manual oversight.
              </p>
            </div>
          </div>
          <div className="max-h-64 overflow-auto rounded-lg border border-amber-200/80 bg-white">
            <table className="min-w-full text-left text-xs text-slate-800">
              <thead className="sticky top-0 bg-amber-100/90 border-b border-amber-200">
                <tr>
                  <th className="px-2 py-1.5">Segment</th>
                  <th className="px-2 py-1.5">Passenger</th>
                  <th className="px-2 py-1.5">Route</th>
                  <th className="px-2 py-1.5">Confidence</th>
                  <th className="px-2 py-1.5">State</th>
                  <th className="px-2 py-1.5">Outcome / basis</th>
                  <th className="px-2 py-1.5">Trace</th>
                  <th className="px-2 py-1.5">Actions</th>
                </tr>
              </thead>
              <tbody>
                {pendingReviewCases.map((s) => (
                  <tr key={s.id} className="border-b border-amber-100/80 hover:bg-amber-50/50">
                    <td className="px-2 py-1 font-mono text-[11px]">{s.id}</td>
                    <td className="px-2 py-1">{s.passengerId}</td>
                    <td className="px-2 py-1">
                      {(s.originStation || s.origin || '—')}
                      {' → '}
                      {(s.destinationStation || s.destination || '—')}
                    </td>
                    <td className="px-2 py-1 whitespace-nowrap">
                      {s.confidenceScore != null ? `${Math.round(Number(s.confidenceScore))}%` : '—'}
                      {s.confidenceBand ? ` (${s.confidenceBand})` : ''}
                    </td>
                    <td className="px-2 py-1">{s.segmentState || '—'}</td>
                    <td className="px-2 py-1 max-w-[200px]">
                      <span className="line-clamp-2" title={[s.decisionOutcome, s.decisionBasis].filter(Boolean).join(' · ')}>
                        {[s.decisionOutcome, s.decisionBasis].filter(Boolean).join(' · ') || '—'}
                      </span>
                    </td>
                    <td className="px-2 py-1 font-mono text-[10px] max-w-[120px] truncate" title={s.traceReference || ''}>
                      {s.traceReference ? `${String(s.traceReference).slice(0, 14)}…` : '—'}
                    </td>
                    <td className="px-2 py-1">
                      <div className="flex flex-wrap gap-1">
                        <button
                          type="button"
                          className="rounded border border-amber-400 bg-white px-2 py-0.5 text-[11px] font-medium text-amber-900 hover:bg-amber-100"
                          onClick={() => setDecisionCardSegmentId(s.id)}
                        >
                          Inspect
                        </button>
                        <button
                          type="button"
                          className="rounded border border-slate-300 bg-white px-2 py-0.5 text-[11px] text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                          onClick={() => handlePendingReviewStateAction(s.id, 'PENDING_REVIEW')}
                          disabled={pendingStateActionId === s.id || s.segmentState === 'PENDING_REVIEW'}
                        >
                          Reopen
                        </button>
                        <button
                          type="button"
                          className="rounded border border-rose-300 bg-white px-2 py-0.5 text-[11px] text-rose-700 hover:bg-rose-50 disabled:opacity-50"
                          onClick={() => handlePendingReviewStateAction(s.id, 'ESCALATED_FRAUD_REVIEW')}
                          disabled={pendingStateActionId === s.id || s.segmentState === 'ESCALATED_FRAUD_REVIEW'}
                        >
                          Escalate
                        </button>
                        <button
                          type="button"
                          className="rounded border border-emerald-300 bg-white px-2 py-0.5 text-[11px] text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
                          onClick={() => handlePendingReviewStateAction(s.id, 'CLOSED')}
                          disabled={pendingStateActionId === s.id || s.segmentState === 'CLOSED'}
                        >
                          Close
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {!pendingReviewCases.length && (
                  <tr>
                    <td colSpan={8} className="px-2 py-6 text-center text-slate-500">No segments in PENDING_REVIEW.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <h2 className="text-sm font-semibold text-slate-900 mb-3">Scenario driver (discrete station events)</h2>
          <form onSubmit={handleSimulateJourney} className="grid gap-3 md:grid-cols-4">
            <input value={simUserId} onChange={(e) => setSimUserId(e.target.value)} placeholder="User ID" className="rounded-lg border border-slate-300 px-3 py-2 text-sm" required />
            <select value={simOriginId} onChange={(e) => setSimOriginId(e.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" required>
              <option value="">Origin geofence</option>
              {geofences.map((g) => <option key={g.id} value={g.id}>{g.stationName || g.name}</option>)}
            </select>
            <select value={simDestId} onChange={(e) => setSimDestId(e.target.value)} className="rounded-lg border border-slate-300 px-3 py-2 text-sm" required>
              <option value="">Destination geofence</option>
              {geofences.map((g) => <option key={g.id} value={g.id}>{g.stationName || g.name}</option>)}
            </select>
            <button type="submit" disabled={simLoading} className="rounded-lg bg-indigo-600 px-4 py-2 text-sm text-white disabled:opacity-50">{simLoading ? 'Running...' : 'Run journey scenario'}</button>
          </form>
        </div>

        <div className="grid gap-4 lg:grid-cols-2 lg:items-start">
          <div
            className={`rounded-xl border-2 p-5 shadow-md ${cardBorder}`}
            role="region"
            aria-label="Journey decision summary"
          >
            <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-600 mb-3">
              Decision summary
            </p>
            {decisionSegment ? (
              <>
                <div className="grid gap-1 text-sm text-slate-800 sm:grid-cols-2">
                  <p>
                    <span className="text-slate-500">Passenger:</span>
                    {' '}
                    <span className="font-semibold">{decisionSegment.passengerId}</span>
                  </p>
                  <p>
                    <span className="text-slate-500">Route:</span>
                    {' '}
                    <span className="font-semibold">
                      {origin}
                      {' '}
                      →
                      {dest}
                    </span>
                  </p>
                  <p className="sm:col-span-2">
                    <span className="text-slate-500">Train:</span>
                    {' '}
                    <span className="font-semibold">{trainLabel || '—'}</span>
                  </p>
                </div>
                <div className="mt-4 space-y-2 text-base">
                  <div className="flex items-center justify-between gap-2">
                    <p className="text-xs text-slate-600">
                      {calcAt ? `Last recalculated: ${calcAt.toLocaleTimeString('en-GB')}` : 'Not recalculated in this session'}
                    </p>
                    <button
                      type="button"
                      onClick={handleCalculateNow}
                      disabled={calcLoading || loading}
                      className="rounded-lg border border-indigo-300 bg-indigo-50 px-3 py-1.5 text-xs font-medium text-indigo-700 hover:bg-indigo-100 disabled:opacity-50"
                    >
                      {calcLoading ? 'Calculating...' : 'Calculate now'}
                    </button>
                  </div>
                  <p className="font-bold text-slate-900">
                    DECISION:
                    {' '}
                    <span className={isPaid ? 'text-emerald-800' : isBad ? 'text-red-800' : 'text-amber-900'}>
                      {decisionCardTitle(decisionSegment.fareStatus)}
                    </span>
                  </p>
                  <p className="text-slate-800">
                    Confidence:
                    {' '}
                    <span className="font-semibold">{formatConfidenceLine(decisionSegment)}</span>
                  </p>
                  <p className="text-slate-800">
                    Reason:
                    {' '}
                    <span className="font-medium">{decisionCardReason(decisionSegment)}</span>
                  </p>
                  <div className="mt-4 rounded-lg border border-slate-200/80 bg-white/70 p-3">
                    <p className="text-[11px] font-semibold uppercase tracking-wide text-slate-600 mb-2">
                      How the system calculated this
                    </p>
                    <ul className="list-disc pl-4 space-y-1.5 text-xs text-slate-800 leading-relaxed">
                      {decisionCardCalculationLines(decisionSegment).map((line, i) => (
                        <li key={i}>{line}</li>
                      ))}
                    </ul>
                  </div>
                  {decisionSegment.traceReference && (
                    <p className="text-xs text-slate-600 font-mono">
                      Trace:
                      {decisionSegment.traceReference}
                    </p>
                  )}
                </div>
                <p className="mt-3 text-xs text-slate-600">
                  Pin another segment from the table below.
                </p>
              </>
            ) : (
              <p className="text-sm text-slate-600">
                No trip segments yet. Run a journey scenario or generate movement to see the decision card.
              </p>
            )}
          </div>

          <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-900 mb-1">Segment map (this trip only)</h2>
            <p className="text-xs text-slate-500 mb-3">
              Only the segment pinned in the decision card: origin and destination geofences, plus that passenger’s entry (green) and exit / arrival (red) from geofence events in the segment time window. Other users are not shown.
            </p>
            <AdminGeofenceMap
              geofences={segmentMapGeofences}
              userLocations={[]}
              highlightedEvents={highlightedMapEvents}
              showUserMarkers={false}
            />
            {decisionSegment && (
              <p className="mt-2 text-xs text-slate-600">
                User <span className="font-medium">{decisionSegment.passengerId}</span>
                {' · '}
                {origin} → {dest}
                {trainLabel ? ` · ${trainLabel}` : ''}
              </p>
            )}
          </div>
        </div>

        <div className="grid gap-4 lg:grid-cols-2">
          <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-slate-900 mb-2">Trip segments (click to pin decision card)</h2>
            <div className="max-h-72 overflow-auto rounded-lg border border-slate-200">
              <table className="min-w-full text-left text-xs text-slate-700">
                <thead className="sticky top-0 bg-slate-50 border-b border-slate-200">
                  <tr>
                    <th className="px-2 py-1">Passenger</th>
                    <th className="px-2 py-1">Route</th>
                    <th className="px-2 py-1">Fare</th>
                    <th className="px-2 py-1">Confidence</th>
                  </tr>
                </thead>
                <tbody>
                  {segments.map((s) => (
                    <tr
                      key={s.id}
                      className={`border-b border-slate-100 cursor-pointer hover:bg-slate-50 ${decisionCardSegmentId === s.id ? 'bg-indigo-50' : ''}`}
                      onClick={() => setDecisionCardSegmentId(s.id)}
                    >
                      <td className="px-2 py-1">{s.passengerId}</td>
                      <td className="px-2 py-1">
                        {(s.originStation || s.origin || '—')}
                        {' → '}
                        {(s.destinationStation || s.destination || '—')}
                      </td>
                      <td className="px-2 py-1 font-medium">{s.fareStatus}</td>
                      <td className="px-2 py-1">{s.confidenceScore != null ? `${Math.round(Number(s.confidenceScore))}%` : '—'}</td>
                    </tr>
                  ))}
                  {!segments.length && (
                    <tr>
                      <td colSpan={4} className="px-2 py-4 text-center text-slate-500">No segments.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>

          <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
            <div className="mb-2 flex items-center justify-between gap-3">
              <h2 className="text-sm font-semibold text-slate-900">Admin review queue</h2>
              <select
                value={disputeStatusFilter}
                onChange={(e) => setDisputeStatusFilter(e.target.value)}
                className="rounded-lg border border-slate-300 bg-white px-2 py-1 text-xs"
              >
                <option value="OPEN">OPEN</option>
                <option value="UNDER_REVIEW">UNDER REVIEW</option>
                <option value="ACCEPTED">ACCEPTED</option>
                <option value="REJECTED">REJECTED</option>
              </select>
            </div>
            <div className="max-h-72 overflow-auto rounded-lg border border-slate-200">
              <table className="min-w-full text-left text-xs text-slate-700">
                <thead className="sticky top-0 bg-slate-50 border-b border-slate-200">
                  <tr>
                    <th className="px-2 py-1">Submitted</th>
                    <th className="px-2 py-1">User</th>
                    <th className="px-2 py-1">Segment</th>
                    <th className="px-2 py-1">Status</th>
                    <th className="px-2 py-1">Action</th>
                  </tr>
                </thead>
                <tbody>
                  {disputes.slice(0, 30).map((d) => (
                    <tr key={d.id} className="border-b border-slate-100">
                      <td className="px-2 py-1 whitespace-nowrap">{formatDate(d.submittedAt)}</td>
                      <td className="px-2 py-1">{d.userId}</td>
                      <td className="px-2 py-1">{d.segmentId}</td>
                      <td className="px-2 py-1">{d.status}</td>
                      <td className="px-2 py-1">
                        <div className="flex gap-1">
                          <button
                            type="button"
                            className="rounded border border-slate-300 px-2 py-0.5 text-[11px] hover:bg-slate-50 disabled:opacity-50"
                            onClick={() => handleDisputeAction(d.id, 'under-review')}
                            disabled={reviewActionId === d.id || d.status === 'UNDER_REVIEW' || d.status === 'ACCEPTED' || d.status === 'REJECTED'}
                          >
                            Review
                          </button>
                          <button
                            type="button"
                            className="rounded border border-emerald-300 px-2 py-0.5 text-[11px] text-emerald-700 hover:bg-emerald-50 disabled:opacity-50"
                            onClick={() => handleDisputeAction(d.id, 'accept')}
                            disabled={reviewActionId === d.id || d.status === 'ACCEPTED'}
                          >
                            Accept
                          </button>
                          <button
                            type="button"
                            className="rounded border border-rose-300 px-2 py-0.5 text-[11px] text-rose-700 hover:bg-rose-50 disabled:opacity-50"
                            onClick={() => handleDisputeAction(d.id, 'reject')}
                            disabled={reviewActionId === d.id || d.status === 'REJECTED'}
                          >
                            Reject
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                  {!disputes.length && <tr><td colSpan={5} className="px-2 py-4 text-center text-slate-500">No disputes in this status.</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default AdminDashboard;

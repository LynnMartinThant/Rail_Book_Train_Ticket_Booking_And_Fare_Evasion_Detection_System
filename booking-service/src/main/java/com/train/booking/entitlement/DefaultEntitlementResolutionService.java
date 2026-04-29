package com.train.booking.entitlement;

import com.train.booking.config.RouteOrderConfig;
import com.train.booking.decision.CoverageResult;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.Trip;
import com.train.booking.domain.TripSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultEntitlementResolutionService implements EntitlementResolutionService {

    @Value("${booking.entitlement.temporal-window-minutes:360}")
    private long temporalWindowMinutes;

    private final RouteOrderConfig routeOrderConfig;

    @Override
    public EntitlementResolution resolve(TripSegment segment, List<Reservation> internalTickets, Map<String, Object> explanationRoot) { // resolve entitlement
        if (segment == null) { // no segment means no minimum data so do not have enough journey data to check the ticket
            return new EntitlementResolution(
                EntitlementState.UNVERIFIED,
                EntitlementSourceType.UNKNOWN,
                CoverageResult.NONE,
                false,
                List.of("Segment is missing; entitlement cannot be evaluated."),
                Map.of("phase", "ENTITLEMENT_RESOLUTION")
            );
        }

        String origin = segment.getOriginStation(); // main validation more on start - end 
        String destination = segment.getDestinationStation();
        Instant start = segment.getSegmentStartTime();
        Instant end = segment.getSegmentEndTime();

        List<String> reasons = new ArrayList<>(); // prepare reasons for the decision
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("phase", "ENTITLEMENT_RESOLUTION");
        ctx.put("segmentOrigin", origin);
        ctx.put("segmentDestination", destination);
        ctx.put("segmentStart", start);
        ctx.put("segmentEnd", end);
        ctx.put("internalTicketCount", internalTickets != null ? internalTickets.size() : 0);
        ctx.put("supportedSources", List.of("INTERNAL_TICKET", "EXTERNAL_QR", "ACCOUNT_BALANCE"));

        CoverageResult bestCoverage = CoverageResult.NONE;
        boolean temporalValid = false;
        Reservation bestReservation = null;

        if (internalTickets != null) { // no internal tickets means no minimum data / if there are internal tickets, check each one
            for (Reservation r : internalTickets) {
                String tFrom = ticketFrom(r);
                String tTo = ticketTo(r);
                if (tFrom == null || tTo == null) continue;
                boolean full = routeOrderConfig.ticketCoversSegment(tFrom, tTo, origin, destination);
                boolean partial = !full && routeOrderConfig.ticketIsShortForSegment(tFrom, tTo, origin, destination);
                boolean temporal = ticketTimeLooksValid(r, start, end);

                if (full) {
                    bestCoverage = CoverageResult.FULL;
                    temporalValid = temporal;
                    bestReservation = r;
                    break;
                } else if (partial && bestCoverage != CoverageResult.PARTIAL) {
                    bestCoverage = CoverageResult.PARTIAL;
                    temporalValid = temporal;
                    bestReservation = r;
                }
            }
        }

        if (bestReservation != null) {
            ctx.put("matchedInternalReservationId", bestReservation.getId());
            ctx.put("matchedInternalTicketOrigin", ticketFrom(bestReservation));
            ctx.put("matchedInternalTicketDestination", ticketTo(bestReservation));
            ctx.put("temporalWindowMinutes", temporalWindowMinutes);
        }

        if (bestCoverage == CoverageResult.FULL && temporalValid) {
            reasons.add("Internal ticket fully covers reconstructed segment and time window is valid.");
            return new EntitlementResolution(
                EntitlementState.COVERED,
                EntitlementSourceType.INTERNAL_TICKET,
                CoverageResult.FULL,
                true,
                reasons,
                ctx
            );
        }

        if (bestCoverage == CoverageResult.PARTIAL) {
            reasons.add("Internal ticket partially covers reconstructed segment (short entitlement / overtravel).");
            if (!temporalValid) reasons.add("Ticket time window appears outside reconstructed journey window.");
            return new EntitlementResolution(
                EntitlementState.NOT_COVERED,
                EntitlementSourceType.INTERNAL_TICKET,
                CoverageResult.PARTIAL,
                temporalValid,
                reasons,
                ctx
            );
        }

 
        reasons.add("No covering internal ticket found for reconstructed segment.");
        reasons.add("External QR or account-balance entitlement not yet verified.");
        return new EntitlementResolution(
            EntitlementState.UNVERIFIED,
            EntitlementSourceType.UNKNOWN,
            CoverageResult.NONE,
            false,
            reasons,
            ctx
        );
    }

    private boolean ticketTimeLooksValid(Reservation r, Instant segmentStart, Instant segmentEnd) {
        if (r == null || r.getTripSeat() == null || r.getTripSeat().getTrip() == null) return false;
        Trip t = r.getTripSeat().getTrip();
        Instant departure = t.getDepartureTime();
       long minutesDiff = Math.abs(ChronoUnit.MINUTES.between(departure, segmentStart)); // major logic fix no external qr since real world system use same api for qr validation and prevent user not to be able to use same ticket for mult travel
    return minutesDiff <= 60;
    }

    private static String ticketFrom(Reservation r) { // fetch from station from ticket
        if (r == null) return null;
        if (r.getJourneyFromStation() != null) return r.getJourneyFromStation();
        if (r.getTripSeat() != null && r.getTripSeat().getTrip() != null) return r.getTripSeat().getTrip().getFromStation();
        return null;
    }

    private static String ticketTo(Reservation r) { // fetch to station from ticket
        if (r == null) return null;
        if (r.getJourneyToStation() != null) return r.getJourneyToStation();
        if (r.getTripSeat() != null && r.getTripSeat().getTrip() != null) return r.getTripSeat().getTrip().getToStation();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean readExternalQrVerified(Map<String, Object> explanationRoot) {
        if (explanationRoot == null) return false;
        Object dispute = explanationRoot.get("disputeResolution");
        if (!(dispute instanceof Map<?, ?> dm)) return false;
        Object rc = ((Map<String, Object>) dm).get("ruleCode");
        return rc != null && "PROOF_ACCEPTED".equalsIgnoreCase(String.valueOf(rc));
    }
}

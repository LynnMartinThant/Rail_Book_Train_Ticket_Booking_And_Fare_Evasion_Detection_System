package com.train.booking.service;

import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.StationEntryAction;
import com.train.booking.domain.UserNotification;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.platform.MovementSourceLayer;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.StationEntryActionRepository;
import com.train.booking.repository.UserNotificationRepository;
import com.train.booking.rules.GeofenceRuleFacts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.Message;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Station entry: emit {@link MovementEventType#StationEntryValidated}, run {@code geofence.drl} for no-ticket,
 * and create {@link StationEntryAction} + notification when appropriate (with Java fallback if Drools fails).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeofenceRulesService {

    private static final String GEOFENCE_DRL = "com/train/booking/rules/geofence.drl";

    private final ReservationRepository reservationRepository;
    private final StationEntryActionRepository stationEntryActionRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final MovementEventWriter movementEventWriter;

    @Transactional
    public void onStationEntry(String userId, String stationName, Long geofenceId) {
        onStationEntry(userId, stationName, geofenceId, null);
    }

    /**
     * If no valid ticket, Drools (or Java fallback) may create a {@link StationEntryAction} and notify the user.
     */
    @Transactional
    public void onStationEntry(String userId, String stationName, Long geofenceId, String correlationId) {
        boolean hasTicket = hasConfirmedEntitlementAtStation(userId, stationName);
        Reservation matched = findMatchedReservation(userId, stationName);
        emitStationEntryValidated(userId, stationName, geofenceId, hasTicket, matched, correlationId);
        runNoTicketDroolsOrFallback(userId, stationName, geofenceId, hasTicket);
    }

    private boolean hasConfirmedEntitlementAtStation(String userId, String stationName) {
        return reservationRepository.existsByUserIdAndStatusAndTripFromStation(
                userId, ReservationStatus.CONFIRMED, stationName)
            || reservationRepository.existsByUserIdAndStatusAndJourneyFromStation(
                userId, ReservationStatus.CONFIRMED, stationName);
    }

    private void runNoTicketDroolsOrFallback(String userId, String stationName, Long geofenceId, boolean hasTicket) {
        try {
            byte[] drlBytes = new ClassPathResource(GEOFENCE_DRL).getInputStream().readAllBytes();
            KieServices ks = KieServices.Factory.get();
            KieFileSystem kfs = ks.newKieFileSystem();
            kfs.write(
                "src/main/resources/" + GEOFENCE_DRL,
                ks.getResources().newByteArrayResource(drlBytes).setResourceType(ResourceType.DRL));
            KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
            if (kb.getResults().hasMessages(Message.Level.ERROR)) {
                log.warn("Drools build errors: {}", kb.getResults());
                notifyNoTicketIfNeeded(userId, stationName, geofenceId, hasTicket);
                return;
            }
            KieModule km = kb.getKieModule();
            KieContainer kc = ks.newKieContainer(km.getReleaseId());
            KieSession session = kc.getKieBase().newKieSession();
            try {
                session.insert(new GeofenceRuleFacts.UserEnteredStation(userId, stationName, geofenceId));
                session.insert(new GeofenceRuleFacts.TicketSearchResult(userId, stationName, hasTicket));
                session.fireAllRules();
                boolean noTicketFact = session.getObjects(o -> o instanceof GeofenceRuleFacts.NoTicketAtEntry).iterator().hasNext();
                if (noTicketFact) {
                    createNoTicketActionAndNotify(userId, stationName, geofenceId);
                }
            } finally {
                session.dispose();
            }
        } catch (Exception e) {
            log.warn("Drools execution failed, using Java fallback: {}", e.getMessage());
            notifyNoTicketIfNeeded(userId, stationName, geofenceId, hasTicket);
        }
    }

    private void notifyNoTicketIfNeeded(String userId, String stationName, Long geofenceId, boolean hasTicket) {
        if (!hasTicket) {
            createNoTicketActionAndNotify(userId, stationName, geofenceId);
        }
    }

    private Reservation findMatchedReservation(String userId, String stationName) {
        List<Reservation> confirmed = reservationRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
            userId, List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID));
        if (stationName == null) {
            return null;
        }
        for (Reservation r : confirmed) {
            String fromTrip = r.getTripSeat() != null && r.getTripSeat().getTrip() != null
                ? r.getTripSeat().getTrip().getFromStation()
                : null;
            String fromJourney = r.getJourneyFromStation();
            if (fromJourney != null && stationName.equalsIgnoreCase(fromJourney)) {
                return r;
            }
            if (fromTrip != null && stationName.equalsIgnoreCase(fromTrip)) {
                return r;
            }
        }
        return null;
    }

    private void emitStationEntryValidated(
        String userId,
        String stationName,
        Long geofenceId,
        boolean hasTicket,
        Reservation matched,
        String correlationId
    ) {
        String corr = correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyStage", "StationEntryValidation");
        payload.put("policyName", "StationEntryValidationPolicy");
        payload.put("stationEntered", stationName);
        payload.put("geofenceId", geofenceId);
        payload.put("validTicketExists", hasTicket);
        payload.put("decision", hasTicket ? "VALID" : "INVALID");
        payload.put("decisionReason", hasTicket
            ? "User has confirmed entitlement for this station entry."
            : "No confirmed ticket entitlement found for this station entry; review required.");

        if (matched != null) {
            payload.put("matchedReservationId", matched.getId());
            Map<String, Object> ticket = new LinkedHashMap<>();
            ticket.put("reservationId", matched.getId());
            ticket.put("status", matched.getStatus() != null ? matched.getStatus().name() : null);
            ticket.put("journeyFromStation", matched.getJourneyFromStation());
            ticket.put("journeyToStation", matched.getJourneyToStation());
            if (matched.getTripSeat() != null && matched.getTripSeat().getTrip() != null) {
                ticket.put("tripFromStation", matched.getTripSeat().getTrip().getFromStation());
                ticket.put("tripToStation", matched.getTripSeat().getTrip().getToStation());
                ticket.put("tripId", matched.getTripSeat().getTrip().getId());
            }
            payload.put("ticketCoverage", ticket);
        } else {
            payload.put("matchedReservationId", null);
        }

        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("policyName", "StationEntryValidationPolicy");
        explanation.put("ruleCode", hasTicket ? "TICKET_FOUND" : "NO_TICKET_FOUND");
        explanation.put("decisionReason", String.valueOf(payload.get("decisionReason")));
        explanation.put("stationEntered", stationName);
        explanation.put("ticketCoverage", payload.get("ticketCoverage"));
        explanation.put("nextAction", hasTicket ? "ALLOW_ENTRY" : "PROMPT_USER_OR_REVIEW");
        payload.put("explanation", explanation);

        movementEventWriter.append(
            userId,
            corr,
            MovementEventType.StationEntryValidated,
            Instant.now(),
            payload,
            MovementSourceLayer.STATION_PROCESSING
        );
    }

    private void createNoTicketActionAndNotify(String userId, String stationName, Long geofenceId) {
        if (stationEntryActionRepository.existsByUserIdAndGeofenceIdAndStatus(userId, geofenceId, StationEntryAction.Status.PENDING_OPTION)) {
            return;
        }
        StationEntryAction action = StationEntryAction.builder()
            .userId(userId)
            .geofenceId(geofenceId)
            .stationName(stationName)
            .status(StationEntryAction.Status.PENDING_OPTION)
            .build();
        stationEntryActionRepository.save(action);
        String message = String.format(
            "You entered %s. No ticket found. Choose: [BUY_TICKET] Purchase a ticket | [IGNORE] I'll risk it | [SCAN_QR] I have a ticket (scan QR)",
            stationName);
        UserNotification n = UserNotification.builder()
            .userId(userId)
            .message(message.length() > 1000 ? message.substring(0, 1000) : message)
            .build();
        userNotificationRepository.save(n);
        log.info("No ticket at entry: created StationEntryAction and notification for user {} at {}", userId, stationName);
    }

    public List<StationEntryAction> findPendingByUser(String userId) {
        return stationEntryActionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, StationEntryAction.Status.PENDING_OPTION);
    }

    @Transactional
    public Optional<StationEntryAction> respondToAction(Long actionId, String userId, StationEntryAction.ResponseType responseType) {
        return stationEntryActionRepository.findByIdAndUserIdForUpdate(actionId, userId)
            .filter(a -> a.getStatus() == StationEntryAction.Status.PENDING_OPTION)
            .map(a -> {
                a.setResponseType(responseType);
                a.setRespondedAt(Instant.now());
                if (responseType == StationEntryAction.ResponseType.IGNORE) {
                    a.setStatus(StationEntryAction.Status.IGNORED);
                } else if (responseType == StationEntryAction.ResponseType.BUY_TICKET) {
                    a.setStatus(StationEntryAction.Status.BOUGHT);
                }
                return stationEntryActionRepository.save(a);
            });
    }

    @Transactional
    public Optional<StationEntryAction> validateQrAndCompleteAction(Long actionId, String userId, Long reservationId) {
        Optional<StationEntryAction> actionOpt = stationEntryActionRepository.findByIdAndUserIdForUpdate(actionId, userId)
            .filter(a -> a.getStatus() == StationEntryAction.Status.PENDING_OPTION);
        if (actionOpt.isEmpty()) {
            return Optional.empty();
        }

        Optional<Reservation> reservation = reservationRepository.findByIdAndUserIdForUpdate(reservationId, userId);
        if (reservation.isEmpty() || reservation.get().getStatus() != ReservationStatus.CONFIRMED) {
            return Optional.empty();
        }
        if (stationEntryActionRepository.existsByQrValidatedReservationId(reservationId)) {
            return Optional.empty();
        }

        Reservation r = reservation.get();
        String entryStation = actionOpt.get().getStationName();
        String ticketOrigin = r.getJourneyFromStation() != null ? r.getJourneyFromStation() : r.getTripSeat().getTrip().getFromStation();
        String ticketDest = r.getJourneyToStation() != null ? r.getJourneyToStation() : r.getTripSeat().getTrip().getToStation();
        boolean atOrigin = entryStation != null && ticketOrigin != null && entryStation.trim().equalsIgnoreCase(ticketOrigin.trim());
        boolean atDestination = entryStation != null && ticketDest != null && entryStation.trim().equalsIgnoreCase(ticketDest.trim());
        if (!atOrigin && !atDestination) {
            return Optional.empty();
        }

        StationEntryAction a = actionOpt.get();
        a.setStatus(StationEntryAction.Status.SCANNED_QR);
        a.setResponseType(StationEntryAction.ResponseType.SCAN_QR);
        a.setQrValidatedReservationId(reservationId);
        a.setRespondedAt(Instant.now());
        return Optional.of(stationEntryActionRepository.save(a));
    }
}

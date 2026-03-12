package com.train.booking.service;

import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.StationEntryAction;
import com.train.booking.domain.UserNotification;
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
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Drools-based automation: on station entry, run ticket search rule.
 * If rule fires "NoTicketAtEntry", create StationEntryAction and notify user with options (Buy / Ignore / Scan QR).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeofenceRulesService {

    private final ReservationRepository reservationRepository;
    private final StationEntryActionRepository stationEntryActionRepository;
    private final UserNotificationRepository userNotificationRepository;

    /**
     * Run rules for user entering station. If no ticket detected, creates StationEntryAction (PENDING_OPTION) and sends notification with options.
     */
    @Transactional
    public void onStationEntry(String userId, String stationName, Long geofenceId) {
        boolean hasTicket = reservationRepository.existsByUserIdAndStatusAndTripFromStation(
            userId, ReservationStatus.CONFIRMED, stationName);

        try {
            byte[] drlBytes = new ClassPathResource("rules/geofence.drl").getInputStream().readAllBytes();
            String drl = new String(drlBytes, StandardCharsets.UTF_8);
            KieServices ks = KieServices.Factory.get();
            KieFileSystem kfs = ks.newKieFileSystem();
            kfs.write("src/main/resources/rules/geofence.drl", ks.getResources().newByteArrayResource(drlBytes).setResourceType(ResourceType.DRL));
            KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
            if (kb.getResults().hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
                log.warn("Drools build errors: {}", kb.getResults().toString());
                if (!hasTicket) createNoTicketActionAndNotify(userId, stationName, geofenceId);
                return;
            }
            KieModule km = kb.getKieModule();
            KieContainer kc = ks.newKieContainer(km.getReleaseId());
            KieSession session = kc.getKieBase().newKieSession();
            try {
                session.insert(new GeofenceRuleFacts.UserEnteredStation(userId, stationName, geofenceId));
                session.insert(new GeofenceRuleFacts.TicketSearchResult(userId, stationName, hasTicket));
                session.fireAllRules();
                Collection<?> results = session.getObjects(o -> o instanceof GeofenceRuleFacts.NoTicketAtEntry);
                if (!results.isEmpty()) {
                    createNoTicketActionAndNotify(userId, stationName, geofenceId);
                }
            } finally {
                session.dispose();
            }
        } catch (Exception e) {
            log.warn("Drools execution failed, using Java fallback: {}", e.getMessage());
            if (!hasTicket) {
                createNoTicketActionAndNotify(userId, stationName, geofenceId);
            }
        }
    }

    private void createNoTicketActionAndNotify(String userId, String stationName, Long geofenceId) {
        if (stationEntryActionRepository.existsByUserIdAndGeofenceIdAndStatus(userId, geofenceId, StationEntryAction.Status.PENDING_OPTION)) {
            return; // idempotent: already a pending action for this user+geofence (concurrent traffic)
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

    /** IGNORE/BUY_TICKET: close action. SCAN_QR: set responseType only; status stays PENDING_OPTION until validateQr. Uses lock to prevent double response. */
    @Transactional
    public Optional<StationEntryAction> respondToAction(Long actionId, String userId, StationEntryAction.ResponseType responseType) {
        return stationEntryActionRepository.findByIdAndUserIdForUpdate(actionId, userId)
            .filter(a -> a.getStatus() == StationEntryAction.Status.PENDING_OPTION)
            .map(a -> {
                a.setResponseType(responseType);
                a.setRespondedAt(java.time.Instant.now());
                if (responseType == StationEntryAction.ResponseType.IGNORE)
                    a.setStatus(StationEntryAction.Status.IGNORED);
                else if (responseType == StationEntryAction.ResponseType.BUY_TICKET)
                    a.setStatus(StationEntryAction.Status.BOUGHT);
                // SCAN_QR: keep PENDING_OPTION until validateQrAndCompleteAction
                return stationEntryActionRepository.save(a);
            });
    }

    /** Validates that reservation belongs to user and is CONFIRMED, then completes the station-entry action with SCANNED_QR.
     * Uses pessimistic locks on action and reservation to prevent double completion and double-use of the same ticket. */
    @Transactional
    public Optional<StationEntryAction> validateQrAndCompleteAction(Long actionId, String userId, Long reservationId) {
        Optional<Reservation> reservation = reservationRepository.findByIdAndUserIdForUpdate(reservationId, userId);
        if (reservation.isEmpty() || reservation.get().getStatus() != ReservationStatus.CONFIRMED) {
            return Optional.empty();
        }
        if (stationEntryActionRepository.existsByQrValidatedReservationId(reservationId)) {
            return Optional.empty(); // ticket already used for another action (double-use)
        }
        return stationEntryActionRepository.findByIdAndUserIdForUpdate(actionId, userId)
            .filter(a -> a.getStatus() == StationEntryAction.Status.PENDING_OPTION)
            .map(a -> {
                a.setStatus(StationEntryAction.Status.SCANNED_QR);
                a.setResponseType(StationEntryAction.ResponseType.SCAN_QR);
                a.setQrValidatedReservationId(reservationId);
                a.setRespondedAt(java.time.Instant.now());
                return stationEntryActionRepository.save(a);
            });
    }
}

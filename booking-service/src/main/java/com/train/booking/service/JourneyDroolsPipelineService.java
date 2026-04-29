package com.train.booking.service;

import com.train.booking.config.RouteOrderConfig;
import com.train.booking.domain.GeofenceEvent;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.repository.GeofenceEventRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TripSegmentRepository;
import com.train.booking.rules.JourneyDroolsFacts;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Legacy in-process pipeline: reconstructs journeys inside Drools and persists segments directly.
 * When {@code booking.movement.event-driven-pipeline-enabled=true}, real-time entry uses movement coordination
 * + central {@link com.train.booking.platform.farepolicy.FarePolicyConsumer} instead; keep this for batch/tests only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JourneyDroolsPipelineService {

    private static final String DRL_PATH = "com/train/booking/rules/journey-fraud.drl";
    private static final int RECENT_EVENTS_LIMIT = 50;

    private final GeofenceEventRepository geofenceEventRepository;
    private final ReservationRepository reservationRepository;
    private final TripSegmentRepository tripSegmentRepository;
    private final RouteOrderConfig routeOrder;
    private final TripSegmentService tripSegmentService;
    private final AuditLogService auditLogService;

    /**
     * Run the full pipeline for a user after new geofence events: validate events, reconstruct journeys, verify tickets, detect fraud, score risk.
     * Creates trip segments from reconstructed journeys and logs fraud alerts / investigation cases.
     */
    @Transactional
    public PipelineResult runPipelineForUser(String userId) {
        List<GeofenceEvent> events = geofenceEventRepository.findByUserIdOrderByCreatedAtDesc(
            userId, PageRequest.of(0, RECENT_EVENTS_LIMIT));
        return runPipeline(userId, events);
    }

    /**
     * Run pipeline with the given events (e.g. when a single new event arrives and we include recent history).
     */
    @Transactional
    public PipelineResult runPipeline(String userId, List<GeofenceEvent> events) {
        PipelineResult result = new PipelineResult();
        if (events == null || events.isEmpty()) {
            return result;
        }

        try {
            KieSession session = newSession();
            if (session == null) {
                log.warn("Drools session not created for journey-fraud rules");
                return result;
            }

            session.setGlobal("routeOrder", routeOrder);
            session.setGlobal("now", Instant.now());

            // Layer 1 input: LocationEvent facts (reverse so chronological order: oldest first helps readability)
            List<GeofenceEvent> ordered = new ArrayList<>(events);
            ordered.sort(Comparator.comparing(GeofenceEvent::getCreatedAt));
            for (GeofenceEvent e : ordered) {
                String type = e.getEventType() == GeofenceEvent.EventType.ENTERED ? "ENTER" : "EXIT";
                String stationId = e.getGeofence().getStationName();
                long ts = e.getCreatedAt().toEpochMilli();
                Double acc = e.getAccuracyMeters();
                session.insert(new JourneyDroolsFacts.LocationEvent(userId, ts, acc, stationId, type));
            }

            // Tickets (Layer 3/4)
            List<Reservation> confirmed = reservationRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(
                userId, List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID));
            for (Reservation r : confirmed) {
                String from = r.getTripSeat().getTrip().getFromStation();
                String to = r.getTripSeat().getTrip().getToStation();
                session.insert(new JourneyDroolsFacts.Ticket(
                    userId, r.getId(), from, to, from + "-" + to, r.getExpiresAt(), r.getStatus().name()));
            }

            // TicketUsage from segments with reservation (Layer 4 – ticket sharing; need all users to detect sharing)
            tripSegmentRepository.findAllWithReservationId().stream()
                .filter(s -> s.getReservationId() != null)
                .forEach(s -> session.insert(new JourneyDroolsFacts.TicketUsage(s.getReservationId(), s.getPassengerId())));

            // RiskScore per user (Layer 5)
            session.insert(new JourneyDroolsFacts.RiskScore(userId, 0));

            session.fireAllRules();

            // Collect results
            Collection<?> journeys = session.getObjects(o -> o instanceof JourneyDroolsFacts.Journey);
            Collection<?> alerts = session.getObjects(o -> o instanceof JourneyDroolsFacts.FraudAlert);
            Collection<?> cases = session.getObjects(o -> o instanceof JourneyDroolsFacts.InvestigationCase);

            for (Object o : journeys) {
                JourneyDroolsFacts.Journey j = (JourneyDroolsFacts.Journey) o;
                Instant start = Instant.ofEpochMilli(j.getStartTimeEpochMillis());
                Instant end = Instant.ofEpochMilli(j.getEndTimeEpochMillis());
                tripSegmentService.createSegmentFromJourney(
                    j.getUserId(), j.getOriginStation(), j.getDestinationStation(), start, end, null, null)
                    .ifPresent(seg -> result.journeysCreated++);
            }
            for (Object o : alerts) {
                JourneyDroolsFacts.FraudAlert a = (JourneyDroolsFacts.FraudAlert) o;
                auditLogService.log(a.getUserId(), a.getAlertType(),
                    "source=drools_pipeline alertType=" + a.getAlertType());
                result.fraudAlerts++;
            }
            for (Object o : cases) {
                JourneyDroolsFacts.InvestigationCase c = (JourneyDroolsFacts.InvestigationCase) o;
                auditLogService.log(c.getUserId(), "INVESTIGATION_CASE", "source=drools_pipeline high_risk");
                result.investigationCases++;
            }

            session.dispose();
        } catch (Exception e) {
            log.warn("Journey Drools pipeline failed for user {}: {}", userId, e.getMessage());
        }
        return result;
    }

    private KieSession newSession() {
        try {
            byte[] drlBytes = new ClassPathResource(DRL_PATH).getInputStream().readAllBytes();
            KieServices ks = KieServices.Factory.get();
            KieFileSystem kfs = ks.newKieFileSystem();
            kfs.write("src/main/resources/" + DRL_PATH, ks.getResources().newByteArrayResource(drlBytes).setResourceType(ResourceType.DRL));
            KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
            if (kb.getResults().hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
                log.warn("Drools build errors for journey-fraud: {}", kb.getResults().toString());
                return null;
            }
            KieModule km = kb.getKieModule();
            KieContainer kc = ks.newKieContainer(km.getReleaseId());
            return kc.getKieBase().newKieSession();
        } catch (Exception e) {
            log.warn("Failed to create Drools session: {}", e.getMessage());
            return null;
        }
    }

    @lombok.Data
    public static class PipelineResult {
        private int journeysCreated;
        private int fraudAlerts;
        private int investigationCases;
    }
}

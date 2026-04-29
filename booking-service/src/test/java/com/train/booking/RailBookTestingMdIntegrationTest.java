package com.train.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.domain.Geofence;
import com.train.booking.repository.GeofenceRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.StationEntryActionRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.repository.TripSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests implementing scenarios from TESTING.md:
 * §2 Geofence + no ticket flow, §3 Validate QR (I have a ticket), §4 Load tests, §5/§6 Payment flow.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RailBookTestingMdIntegrationTest {

    private static final String ADMIN_SECRET = "admin123";
    private static final String ADMIN_HEADER = "X-Admin-Secret";
    private static final String USER_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private GeofenceRepository geofenceRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private StationEntryActionRepository stationEntryActionRepository;
    @Autowired
    private TripSeatRepository tripSeatRepository;

    private Long geofenceId;
    private Long tripId;
    private Long seatId;
    private Long seatIdSecond;
    private Long seatIdThird;
    private Long seatIdFourth;

    @BeforeEach
    void setUp() {
        geofenceId = geofenceRepository.findAll().stream()
            .filter(g -> "Sheffield".equalsIgnoreCase(g.getStationName()))
            .map(Geofence::getId)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Test data missing Sheffield geofence"));
        tripId = tripRepository.findAllWithTrain().stream().findFirst().map(t -> t.getId()).orElse(1L);
        var tripSeats = tripSeatRepository.findByTripId(tripId);
        seatId = tripSeats.stream().findFirst().map(ts -> ts.getSeat().getId()).orElse(1L);
        seatIdSecond = tripSeats.size() > 1 ? tripSeats.get(1).getSeat().getId() : seatId;
        seatIdThird = tripSeats.size() > 2 ? tripSeats.get(2).getSeat().getId() : seatId;
        seatIdFourth = tripSeats.size() > 3 ? tripSeats.get(3).getSeat().getId() : seatId;
    }

    // ---------- §2 Test geofence + "no ticket" flow ----------

    @Test
    void whenUserEntersGeofenceWithoutTicket_thenPendingStationEntryActionIsCreated() throws Exception {
        String userId = "test-user-no-ticket";

        // Record ENTERED for user (no CONFIRMED ticket from this station)
        mockMvc.perform(post("/api/admin/geofence-events")
                .header(ADMIN_HEADER, ADMIN_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", userId,
                    "geofenceId", geofenceId,
                    "eventType", "ENTERED"
                ))))
            .andExpect(status().isOk());

        // GET pending actions as that user
        ResultActions result = mockMvc.perform(get("/api/station-entry-actions/pending")
                .header(USER_HEADER, userId))
            .andExpect(status().isOk());
        String body = result.andReturn().getResponse().getContentAsString();
        List<?> list = objectMapper.readValue(body, List.class);
        assertThat(list).hasSize(1);
    }

    // ---------- §3 Test "I have a ticket" (Validate QR) ----------

    @Test
    void whenUserValidatesQrWithConfirmedReservation_thenActionCompletes() throws Exception {
        String email = "qr" + System.currentTimeMillis() + "@test.com";
        String password = "pass123";
        SeatSelection seatSelection = pickFreeSeatSelection();
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk());
        String loginBody = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = objectMapper.readValue(loginBody, Map.class);
        String userId = String.valueOf(auth.get("userId"));

        mockMvc.perform(post("/api/reserve")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("tripId", seatSelection.tripId(), "seatIds", List.of(seatSelection.seatId())))))
            .andExpect(status().isOk());
        List<com.train.booking.domain.Reservation> reserved = reservationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(reserved).isNotEmpty();
        long reservationId = reserved.get(0).getId();

        mockMvc.perform(post("/api/reservations/" + reservationId + "/create-payment-intent")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("gateway", "STRIPE"))))
            .andExpect(status().isOk());
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus()).isEqualTo(com.train.booking.domain.ReservationStatus.CONFIRMED);

        // Enter trip destination: no "ticket at this station" match for trip-from rules, so pending action is created;
        // QR validation still succeeds (ticket covers this station as destination).
        mockMvc.perform(post("/api/admin/geofence-events")
                .header(ADMIN_HEADER, ADMIN_SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "userId", userId,
                    "geofenceId", geofenceId,
                    "eventType", "ENTERED"
                ))))
            .andExpect(status().isOk());

        List<com.train.booking.domain.StationEntryAction> pending = stationEntryActionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, com.train.booking.domain.StationEntryAction.Status.PENDING_OPTION);
        assertThat(pending).isNotEmpty();
        long actionId = pending.get(0).getId();

        // Validate QR with reservation ID
        mockMvc.perform(post("/api/station-entry-actions/" + actionId + "/validate-qr")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reservationId", reservationId))))
            .andExpect(status().isOk());

        // Pending list for user should be empty (action completed)
        mockMvc.perform(get("/api/station-entry-actions/pending").header(USER_HEADER, userId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- §4.1 Load test: concurrent geofence entries ----------

    @Test
    void loadTest_concurrentGeofenceEntries_returnsSuccessCount() throws Exception {
        int concurrentUsers = 50;
        String body = mockMvc.perform(post("/api/admin/load-test/geofence-entries")
                .header(ADMIN_HEADER, ADMIN_SECRET)
                .param("geofenceId", String.valueOf(geofenceId))
                .param("concurrentUsers", String.valueOf(concurrentUsers)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Map<?, ?> result = objectMapper.readValue(body, Map.class);
        assertThat(result.get("successCount")).isEqualTo(concurrentUsers);
        assertThat(result.get("failureCount")).isEqualTo(0);
        assertThat(result.get("totalRequested")).isEqualTo(concurrentUsers);
    }

    // ---------- §5 / §6 Payment flow: reserve → payment (demo) → confirm ----------

    @Test
    void paymentFlow_reserveThenDemoPayment_thenReservationIsConfirmed() throws Exception {
        String userId = "payment-test-user";
        SeatSelection seatSelection = pickFreeSeatSelection();
        mockMvc.perform(post("/api/reserve")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("tripId", seatSelection.tripId(), "seatIds", List.of(seatSelection.seatId())))))
            .andExpect(status().isOk());

        List<com.train.booking.domain.Reservation> list = reservationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(list).isNotEmpty();
        long reservationId = list.get(0).getId();
        assertThat(list.get(0).getStatus()).isEqualTo(com.train.booking.domain.ReservationStatus.RESERVED);

        // Demo mode: create-payment-intent marks PAID then CONFIRMED
        mockMvc.perform(post("/api/reservations/" + reservationId + "/create-payment-intent")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("gateway", "STRIPE"))))
            .andExpect(status().isOk());

        com.train.booking.domain.Reservation after = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(com.train.booking.domain.ReservationStatus.CONFIRMED);
    }

    // ---------- Double booking: same seat cannot be reserved by two users ----------

    @Test
    void doubleBooking_secondReserveSameSeat_fails() throws Exception {
        String userA = "double-booking-user-a";
        String userB = "double-booking-user-b";
        SeatSelection seatSelection = pickFreeSeatSelection();
        Long seat = seatSelection.seatId();
        mockMvc.perform(post("/api/reserve")
                .header(USER_HEADER, userA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("tripId", seatSelection.tripId(), "seatIds", List.of(seat)))))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/reserve")
                .header(USER_HEADER, userB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("tripId", seatSelection.tripId(), "seatIds", List.of(seat)))))
            .andExpect(status().isBadRequest());
        assertThat(reservationRepository.findByUserIdOrderByCreatedAtDesc(userB)).isEmpty();
    }

    // ---------- Dynamic pricing (Drools): reserve uses fare bucket tier ----------

    @Test
    void dynamicPricing_reserveUsesDroolsTier() throws Exception {
        String userId = "pricing-test-user";
        SeatSelection seatSelection = pickFreeSeatSelection();
        mockMvc.perform(post("/api/reserve")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("tripId", seatSelection.tripId(), "seatIds", List.of(seatSelection.seatId())))))
            .andExpect(status().isOk());
        List<com.train.booking.domain.Reservation> list = reservationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(list).isNotEmpty();
        java.math.BigDecimal amount = list.get(0).getAmount();
        assertThat(amount).isIn(
            new java.math.BigDecimal("8.00"), new java.math.BigDecimal("9.00"), new java.math.BigDecimal("10.00"));
    }

    @Test
    void demoResetAndReplay_exposeDeterministicScenarioControls() throws Exception {
        String resetBody = mockMvc.perform(post("/api/admin/demo/reset")
                .header(ADMIN_HEADER, ADMIN_SECRET))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> reset = objectMapper.readValue(resetBody, Map.class);
        assertThat(reset.get("status")).isEqualTo("ok");

        String healthBody = mockMvc.perform(get("/api/admin/demo/health")
                .header(ADMIN_HEADER, ADMIN_SECRET))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> health = objectMapper.readValue(healthBody, Map.class);
        assertThat(health.get("bookingsReady")).isEqualTo(Boolean.TRUE);

        String replayBody = mockMvc.perform(post("/api/admin/demo/replay/LOW_QUALITY")
                .header(ADMIN_HEADER, ADMIN_SECRET))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> replay = objectMapper.readValue(replayBody, Map.class);
        assertThat(replay.get("scenario")).isEqualTo("LOW_QUALITY");
        assertThat(replay).containsKeys("segmentId", "segmentFareStatus", "segmentConfidenceScore");

    }

    private SeatSelection pickFreeSeatSelection() {
        Instant now = Instant.now();
        List<com.train.booking.domain.ReservationStatus> active = List.of(
            com.train.booking.domain.ReservationStatus.RESERVED,
            com.train.booking.domain.ReservationStatus.PENDING_PAYMENT,
            com.train.booking.domain.ReservationStatus.PAYMENT_PROCESSING,
            com.train.booking.domain.ReservationStatus.PAID,
            com.train.booking.domain.ReservationStatus.CONFIRMED
        );
        for (var trip : tripRepository.findAllWithTrain()) {
            for (var ts : tripSeatRepository.findByTripId(trip.getId())) {
                if (reservationRepository.findActiveByTripSeatId(ts.getId(), active, now).isEmpty()) {
                    return new SeatSelection(trip.getId(), ts.getSeat().getId());
                }
            }
        }
        throw new IllegalStateException("No free seat available in test dataset");
    }

    private record SeatSelection(Long tripId, Long seatId) {
    }
}

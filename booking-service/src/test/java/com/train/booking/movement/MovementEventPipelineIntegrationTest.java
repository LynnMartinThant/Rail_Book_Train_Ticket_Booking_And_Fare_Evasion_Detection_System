package com.train.booking.movement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.domain.Geofence;
import com.train.booking.movement.eventlog.MovementEventEntity;
import com.train.booking.movement.eventlog.MovementEventRepository;
import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.projection.PassengerMovementViewRepository;
import com.train.booking.repository.GeofenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "booking.movement.streams-enabled=false",
    "booking.movement.event-driven-pipeline-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MovementEventPipelineIntegrationTest {

    private static final String USER_HEADER = "X-User-Id";
    private static final String ADMIN_HEADER = "X-Admin-Secret";
    private static final String ADMIN_SECRET = "admin123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MovementEventRepository movementEventRepository;
    @Autowired
    private GeofenceRepository geofenceRepository;
    @Autowired
    private MovementEventWriter movementEventWriter;
    @Autowired
    private PassengerMovementViewRepository passengerMovementViewRepository;

    private Geofence g1;
    private Geofence g2;

    @BeforeEach
    void setUp() {
        List<Geofence> geofences = geofenceRepository.findAll().stream()
            .sorted(Comparator.comparing(Geofence::getId))
            .toList();
        assertThat(geofences.size()).isGreaterThan(1);
        g1 = geofences.get(0);
        g2 = geofences.get(1);
    }

    @Test
    void locationApi_appendsMovementEvents_andBuildsReadModel_andEmitsJourneyAndFareEvents() throws Exception {
        String userId = "movement-int-" + System.currentTimeMillis();

        // 1) /api/location appends LocationReported
        postLocation(userId, 0.0, 0.0, 10.0);

        // 2) deterministic movement transitions for event-driven pipeline
        Map<String, Object> exitedPayload = new LinkedHashMap<>();
        exitedPayload.put("geofenceId", g1.getId());
        exitedPayload.put("stationName", g1.getStationName());
        exitedPayload.put("accuracyMeters", 5.0);
        movementEventWriter.append(userId, "corr-" + userId + "-1", MovementEventType.GeofenceExited, java.time.Instant.now().minusSeconds(120), exitedPayload);

        Map<String, Object> enteredPayload = new LinkedHashMap<>();
        enteredPayload.put("geofenceId", g2.getId());
        enteredPayload.put("stationName", g2.getStationName());
        enteredPayload.put("accuracyMeters", 5.0);
        movementEventWriter.append(userId, "corr-" + userId + "-1", MovementEventType.GeofenceEntered, java.time.Instant.now(), enteredPayload);

        List<MovementEventEntity> events = movementEventRepository.findByUserIdOrderByRecordedAtAsc(
            userId, org.springframework.data.domain.PageRequest.of(0, 200)).getContent();
        List<String> types = events.stream().map(e -> e.getEventType().name()).collect(Collectors.toList());

        assertThat(types).contains("LocationReported");
        assertThat(types).contains("GeofenceEntered");
        assertThat(types).contains("GeofenceExited");
        assertThat(types).contains("JourneySegmentConfirmed");
        assertThat(types).contains("FareValidated");
        assertThat(passengerMovementViewRepository.findById(userId)).isPresent();

        String movementJson = mockMvc.perform(get("/api/admin/passenger-movement")
                .header(ADMIN_HEADER, ADMIN_SECRET))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> movement = objectMapper.readValue(movementJson, List.class);
        assertThat(movement.stream().anyMatch(v -> userId.equals(String.valueOf(v.get("userId"))))).isTrue();
    }

    @Test
    void tripSegmentAdminApi_exposesStructuredExplanation() throws Exception {
        String userId = "movement-exp-" + System.currentTimeMillis();

        // Trigger one deterministic segment in event-driven pipeline
        Map<String, Object> exitedPayload = new LinkedHashMap<>();
        exitedPayload.put("geofenceId", g1.getId());
        exitedPayload.put("stationName", g1.getStationName());
        exitedPayload.put("accuracyMeters", 5.0);
        movementEventWriter.append(userId, "corr-" + userId + "-2", MovementEventType.GeofenceExited, java.time.Instant.now().minusSeconds(100), exitedPayload);

        Map<String, Object> enteredPayload = new LinkedHashMap<>();
        enteredPayload.put("geofenceId", g2.getId());
        enteredPayload.put("stationName", g2.getStationName());
        enteredPayload.put("accuracyMeters", 5.0);
        movementEventWriter.append(userId, "corr-" + userId + "-2", MovementEventType.GeofenceEntered, java.time.Instant.now(), enteredPayload);

        String body = mockMvc.perform(get("/api/admin/trip-segments")
                .header(ADMIN_HEADER, ADMIN_SECRET)
                .param("passengerId", userId))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segments = objectMapper.readValue(body, List.class);
        assertThat(segments).isNotEmpty();
        Map<String, Object> segment = segments.get(0);
        assertThat(segment.get("origin")).isNotNull();
        assertThat(segment.get("destination")).isNotNull();
        assertThat(segment.get("decisionOutcome")).isNotNull();
        assertThat(segment.get("decisionBasis")).isEqualTo("TICKET_VS_SEGMENT");
        assertThat(segment.get("traceReference")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> explanation = (Map<String, Object>) segment.get("explanation");
        assertThat(explanation).isNotNull();
        assertThat(explanation.get("schemaVersion")).isEqualTo(1);
        assertThat(explanation).containsKey("fareDecision");
        assertThat(explanation).containsKey("reconstructionConfidence");
    }

    @Test
    void movementObservability_endpointsExpose_explanations_andMetrics() throws Exception {
        String userId = "movement-obs-" + System.currentTimeMillis();

        Map<String, Object> exitedPayload = new LinkedHashMap<>();
        exitedPayload.put("geofenceId", g1.getId());
        exitedPayload.put("stationName", g1.getStationName());
        exitedPayload.put("accuracyMeters", 5.0);
        movementEventWriter.append(userId, "corr-" + userId + "-3", MovementEventType.GeofenceExited, java.time.Instant.now().minusSeconds(90), exitedPayload);

        Map<String, Object> enteredPayload = new LinkedHashMap<>();
        enteredPayload.put("geofenceId", g2.getId());
        enteredPayload.put("stationName", g2.getStationName());
        enteredPayload.put("accuracyMeters", 5.0);
        movementEventWriter.append(userId, "corr-" + userId + "-3", MovementEventType.GeofenceEntered, java.time.Instant.now(), enteredPayload);

        String eventsBody = mockMvc.perform(get("/api/admin/movement-events")
                .header(ADMIN_HEADER, ADMIN_SECRET)
                .param("userId", userId)
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = objectMapper.readValue(eventsBody, List.class);
        assertThat(events).isNotEmpty();
        assertThat(events.stream().anyMatch(e -> "FareValidated".equals(String.valueOf(e.get("eventType"))))).isTrue();
        assertThat(events.stream()
            .filter(e -> "FareValidated".equals(String.valueOf(e.get("eventType"))))
            .anyMatch(e -> e.get("explanation") != null)).isTrue();

        String appendMetricBody = mockMvc.perform(get("/actuator/metrics/movement.events.append.count"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> appendMetric = objectMapper.readValue(appendMetricBody, Map.class);
        assertThat(appendMetric.get("name")).isEqualTo("movement.events.append.count");

        String policyMetricBody = mockMvc.perform(get("/actuator/metrics/movement.policy.count"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> policyMetric = objectMapper.readValue(policyMetricBody, Map.class);
        assertThat(policyMetric.get("name")).isEqualTo("movement.policy.count");
    }

    private void postLocation(String userId, double lat, double lon, double accuracy) throws Exception {
        mockMvc.perform(post("/api/location")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "latitude", lat,
                    "longitude", lon,
                    "accuracyMeters", accuracy
                ))))
            .andExpect(status().isOk());
    }
}


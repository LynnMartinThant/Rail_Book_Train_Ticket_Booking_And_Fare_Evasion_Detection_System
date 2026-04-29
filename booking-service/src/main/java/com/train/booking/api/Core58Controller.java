package com.train.booking.api;

import com.train.booking.api.dto.ReportLocationRequest;
import com.train.booking.api.dto.TripSegmentDto;
import com.train.booking.domain.TripSegment;
import com.train.booking.domain.UserLocation;
import com.train.booking.movement.eventlog.MovementEventEntity;
import com.train.booking.movement.eventlog.MovementEventRepository;
import com.train.booking.repository.TripSegmentRepository;
import com.train.booking.service.LocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/core58")
@RequiredArgsConstructor
@Profile("core58")
@CrossOrigin(origins = {"http://localhost:3000", "http://127.0.0.1:3000", "capacitor://localhost", "http://localhost"})
public class Core58Controller {

    private static final String USER_HEADER = "X-User-Id";

    private final LocationService locationService;
    private final TripSegmentRepository tripSegmentRepository;
    private final MovementEventRepository movementEventRepository;

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "core58");
        payload.put("status", "UP");
        return payload;
    }

    @PostMapping("/location/report")
    public Map<String, Object> reportLocation(@RequestHeader(USER_HEADER) String userId,
                                              @Valid @RequestBody ReportLocationRequest request) {
        UserLocation loc = locationService.reportLocation(userId, request.getLatitude(), request.getLongitude(), request.getAccuracyMeters());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", loc.getUserId());
        payload.put("latitude", loc.getLatitude());
        payload.put("longitude", loc.getLongitude());
        payload.put("updatedAt", loc.getUpdatedAt());
        return payload;
    }

    @GetMapping("/segments/{userId}")
    public List<TripSegmentDto> segments(@PathVariable String userId,
                                         @RequestParam(defaultValue = "50") int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        return tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc(userId, PageRequest.of(0, size)).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @GetMapping("/movement-events/{userId}")
    public List<Map<String, Object>> movementEvents(@PathVariable String userId,
                                                    @RequestParam(defaultValue = "100") int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        return movementEventRepository.findByUserIdOrderByRecordedAtDesc(userId, PageRequest.of(0, size))
                .stream()
                .map(this::toEventRow)
                .collect(Collectors.toList());
    }

    private TripSegmentDto toDto(TripSegment s) {
        return TripSegmentDto.builder()
                .id(s.getId())
                .passengerId(s.getPassengerId())
                .origin(s.getOriginStation())
                .destination(s.getDestinationStation())
                .originStation(s.getOriginStation())
                .destinationStation(s.getDestinationStation())
                .originPlatform(s.getOriginPlatform())
                .destinationPlatform(s.getDestinationPlatform())
                .segmentStartTime(s.getSegmentStartTime())
                .segmentEndTime(s.getSegmentEndTime())
                .fareStatus(s.getFareStatus() != null ? s.getFareStatus().name() : null)
                .resolutionDeadline(s.getResolutionDeadline())
                .paidFare(s.getPaidFare())
                .additionalFare(s.getAdditionalFare())
                .penaltyAmount(s.getPenaltyAmount())
                .createdAt(s.getCreatedAt())
                .matchedTripId(s.getMatchedTripId())
                .confidenceScore(s.getConfidenceScore())
                .segmentState(s.getSegmentState() != null ? s.getSegmentState().name() : null)
                .build();
    }

    private Map<String, Object> toEventRow(MovementEventEntity event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventId", event.getEventId());
        row.put("userId", event.getUserId());
        row.put("correlationId", event.getCorrelationId());
        row.put("eventType", event.getEventType() != null ? event.getEventType().name() : null);
        row.put("sourceLayer", event.getSourceLayer() != null ? event.getSourceLayer().name() : null);
        row.put("occurredAt", event.getOccurredAt());
        row.put("recordedAt", event.getRecordedAt());
        row.put("payloadJson", event.getPayloadJson());
        return row;
    }
}

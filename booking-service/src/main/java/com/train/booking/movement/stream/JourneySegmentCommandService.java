package com.train.booking.movement.stream;

import com.train.booking.movement.eventlog.MovementEventType;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.platform.MovementSourceLayer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class JourneySegmentCommandService {

    private final MovementEventWriter movementEventWriter;
    private final MovementPipelineMetrics movementPipelineMetrics;

    public void emitJourneySegmentConfirmed(String userId, String correlationId, String originStation, String destinationStation, Instant startTime, Instant endTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("originStation", originStation);
        payload.put("destinationStation", destinationStation);
        payload.put("segmentStartTime", startTime);
        payload.put("segmentEndTime", endTime);
        movementEventWriter.append(
            userId,
            correlationId,
            MovementEventType.JourneySegmentConfirmed,
            endTime != null ? endTime : Instant.now(),
            payload,
            MovementSourceLayer.JOURNEY_COORDINATION
        );
        movementPipelineMetrics.recordJourneySegmentConfirmed();
    }
}

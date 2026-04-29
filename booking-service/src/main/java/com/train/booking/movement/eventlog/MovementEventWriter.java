package com.train.booking.movement.eventlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.platform.MovementSourceLayer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MovementEventWriter {

    private final MovementEventRepository movementEventRepository;
    private final MovementEventStream movementEventStream;
    private final ObjectMapper objectMapper;
    private final MovementPipelineMetrics metrics;

    /**
     * Append with default {@link MovementSourceLayer#UNKNOWN} (backward compatible).
     */
    @Transactional
    public MovementEventEntity append(String userId, String correlationId, MovementEventType type, Instant occurredAt, Object payload) {
        return append(userId, correlationId, type, occurredAt, payload, MovementSourceLayer.UNKNOWN);
    }

    /**
     * Append to durable event log first, then publish to Kafka/in-process stream.
     */
    @Transactional
    public MovementEventEntity append(String userId, String correlationId, MovementEventType type, Instant occurredAt, Object payload,
                                        MovementSourceLayer sourceLayer) {
        Instant started = Instant.now();
        Instant recordedAt = Instant.now();
        String eventId = UUID.randomUUID().toString();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize movement event payload: " + type, e);
        }

        MovementSourceLayer layer = sourceLayer != null ? sourceLayer : MovementSourceLayer.UNKNOWN;
        MovementEventEntity entity = MovementEventEntity.builder()
            .eventId(eventId)
            .userId(userId)
            .correlationId(correlationId != null && !correlationId.isBlank() ? correlationId : UUID.randomUUID().toString())
            .eventType(type)
            .sourceLayer(layer)
            .occurredAt(occurredAt != null ? occurredAt : recordedAt)
            .recordedAt(recordedAt)
            .payloadJson(payloadJson)
            .build();
        entity = movementEventRepository.save(entity);

        movementEventStream.publish(MovementEventEnvelope.builder()
            .eventId(entity.getEventId())
            .userId(entity.getUserId())
            .correlationId(entity.getCorrelationId())
            .eventType(entity.getEventType().name())
            .occurredAt(entity.getOccurredAt())
            .recordedAt(entity.getRecordedAt())
            .sourceLayer(layer.name())
            .payload(payload)
            .build());

        metrics.recordAppend(Duration.between(started, Instant.now()), type != null ? type.name() : null, layer.name());

        return entity;
    }
}

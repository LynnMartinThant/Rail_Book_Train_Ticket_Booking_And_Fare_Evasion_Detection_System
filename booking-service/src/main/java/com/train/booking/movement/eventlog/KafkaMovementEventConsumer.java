package com.train.booking.movement.eventlog;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Bridge Kafka movement events back into Spring event listeners (projections/policies).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("!'${spring.kafka.bootstrap-servers:}'.trim().isEmpty()")
public class KafkaMovementEventConsumer {

    private final ApplicationEventPublisher publisher;

    @KafkaListener(topics = MovementEventStream.TOPIC_MOVEMENT_EVENTS, groupId = "booking-service-movement-consumers")
    public void onMessage(MovementEventEnvelope envelope) {
        publisher.publishEvent(new MovementEventRecordedEvent(this, envelope));
    }
}


package com.train.booking.movement.eventlog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes movement-domain events to Kafka (if configured) or in-process (Spring events).
 * DB event log write happens before publish (see MovementEventWriter).
 */
@Service
@Slf4j
public class MovementEventStream {

    public static final String TOPIC_MOVEMENT_EVENTS = "movement.events";

    @Value("${spring.kafka.bootstrap-servers:}")
    private String bootstrapServers;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final KafkaTemplate<String, MovementEventEnvelope> kafkaTemplate;

    public MovementEventStream(
        ApplicationEventPublisher applicationEventPublisher,
        @Autowired(required = false) KafkaTemplate<String, MovementEventEnvelope> kafkaTemplate
    ) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.kafkaTemplate = kafkaTemplate != null ? kafkaTemplate : null;
    }

    public void publish(MovementEventEnvelope envelope) {
        if (kafkaTemplate != null && bootstrapServers != null && !bootstrapServers.isBlank()) {
            try {
                kafkaTemplate.send(TOPIC_MOVEMENT_EVENTS, envelope.getUserId(), envelope);
                log.debug("Published to Kafka {}: userId={} type={} id={}", TOPIC_MOVEMENT_EVENTS, envelope.getUserId(), envelope.getEventType(), envelope.getEventId());
                return;
            } catch (Exception e) {
                log.warn("Kafka publish failed, falling back to in-process: {}", e.getMessage());
            }
        }
        applicationEventPublisher.publishEvent(new MovementEventRecordedEvent(this, envelope));
    }
}


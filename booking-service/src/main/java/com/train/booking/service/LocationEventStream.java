package com.train.booking.service;

import com.train.booking.event.GeofenceEventMessage;
import com.train.booking.event.GeofenceEventRecordedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Event stream: publishes geofence events to Kafka (if configured) or in-process (Spring events).
 * Pipeline: Mobile → Location Event Service → [Kafka / Queue] → Drools → Warning / Travel / Violation → Admin / Penalty.
 */
@Service
@Slf4j
public class LocationEventStream {

    public static final String TOPIC_LOCATION_EVENTS = "location-events";

    @Value("${spring.kafka.bootstrap-servers:}")
    private String bootstrapServers;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final KafkaTemplate<String, GeofenceEventMessage> kafkaTemplate;

    public LocationEventStream(ApplicationEventPublisher applicationEventPublisher,
                               @Autowired(required = false) KafkaTemplate<String, GeofenceEventMessage> kafkaTemplate) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.kafkaTemplate = kafkaTemplate != null ? kafkaTemplate : null;
    }

    /**
     * Publish geofence event to the stream. Uses Kafka if bootstrap-servers set, else in-process events.
     */
    public void publish(GeofenceEventMessage message) {
        if (kafkaTemplate != null && bootstrapServers != null && !bootstrapServers.isBlank()) {
            try {
                kafkaTemplate.send(TOPIC_LOCATION_EVENTS, message.getUserId(), message);
                log.debug("Published to Kafka {}: userId={} geofenceId={} {}", TOPIC_LOCATION_EVENTS, message.getUserId(), message.getGeofenceId(), message.getEventType());
            } catch (Exception e) {
                log.warn("Kafka publish failed, falling back to in-process: {}", e.getMessage());
                publishInProcess(message);
            }
        } else {
            publishInProcess(message);
        }
    }

    private void publishInProcess(GeofenceEventMessage message) {
        applicationEventPublisher.publishEvent(new GeofenceEventRecordedEvent(this,
            message.getUserId(), message.getGeofenceId(), message.getEventType(),
            message.getStationName(), message.getCreatedAt(), message.getAccuracyMeters(), message.getCorrelationId()));
    }
}

package com.train.booking.event;

import com.train.booking.service.LocationEventStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes geofence events from Kafka when spring.kafka.bootstrap-servers is set.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression("!'${spring.kafka.bootstrap-servers:}'.trim().isEmpty()")
@RequiredArgsConstructor
@Slf4j
public class KafkaLocationEventConsumer {

    private final LocationEventPipelineConsumer pipelineConsumer;

    @KafkaListener(topics = LocationEventStream.TOPIC_LOCATION_EVENTS, groupId = "booking-service-pipeline")
    public void onMessage(GeofenceEventMessage message) {
        pipelineConsumer.handle(
            message.getUserId(),
            message.getGeofenceId(),
            message.getEventType(),
            message.getStationName(),
            message.getCreatedAt()
        );
    }
}

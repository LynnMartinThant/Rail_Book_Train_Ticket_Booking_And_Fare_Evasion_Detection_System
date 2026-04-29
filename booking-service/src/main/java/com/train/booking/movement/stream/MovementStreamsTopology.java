package com.train.booking.movement.stream;

import com.train.booking.movement.eventlog.MovementEventEnvelope;
import com.train.booking.movement.eventlog.MovementEventStream;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.common.utils.Bytes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Configuration
@EnableKafkaStreams
@RequiredArgsConstructor
@ConditionalOnExpression("!'${spring.kafka.bootstrap-servers:}'.trim().isEmpty()")
@ConditionalOnProperty(value = "booking.movement.streams-enabled", havingValue = "true")
public class MovementStreamsTopology {

    private final JourneySegmentCommandService journeySegmentCommandService;

    @Bean
    public KStream<String, MovementEventEnvelope> movementStream(StreamsBuilder builder) {
        JsonSerde<MovementEventEnvelope> eventSerde = new JsonSerde<>(MovementEventEnvelope.class);
        JsonSerde<PassengerMovementState> stateSerde = new JsonSerde<>(PassengerMovementState.class);

        KStream<String, MovementEventEnvelope> events = builder.stream(
            MovementEventStream.TOPIC_MOVEMENT_EVENTS,
            Consumed.with(Serdes.String(), eventSerde)
        );

        var stateTable = events
            .filter((k, e) -> e != null && e.getUserId() != null)
            .filter((k, e) -> "GeofenceEntered".equals(e.getEventType()) || "GeofenceExited".equals(e.getEventType()))
            .groupByKey(Grouped.with(Serdes.String(), eventSerde))
            .aggregate(
                PassengerMovementState::new,
                (userId, event, state) -> reduceEvent(event, state),
                Materialized.<String, PassengerMovementState, KeyValueStore<Bytes, byte[]>>as("passenger-movement-state-store")
                    .withKeySerde(Serdes.String())
                    .withValueSerde(stateSerde)
            );

        stateTable.toStream(Named.as("movement-state-updates"))
            .filter((userId, state) -> isSegmentReady(state))
            .foreach((userId, state) -> {
                String correlationId = state.getLastProcessedEventId() != null ? state.getLastProcessedEventId() : UUID.randomUUID().toString();
                journeySegmentCommandService.emitJourneySegmentConfirmed(
                    userId,
                    correlationId,
                    state.getCandidateOriginStation(),
                    state.getCurrentStation(),
                    state.getCandidateOriginTime(),
                    state.getLastEventAt()
                );
            });

        return events;
    }

    private static PassengerMovementState reduceEvent(MovementEventEnvelope e, PassengerMovementState s) {
        if (s == null) s = PassengerMovementState.builder().build();
        if (e.getEventId() != null && e.getEventId().equals(s.getLastProcessedEventId())) return s;

        String station = stationFromPayload(e.getPayload());
        String platform = platformFromPayload(e.getPayload());
        Instant at = e.getOccurredAt() != null ? e.getOccurredAt() : Instant.now();
        s.setUserId(e.getUserId());
        s.setLastProcessedEventId(e.getEventId());
        s.setLastGeofenceEventType("GeofenceEntered".equals(e.getEventType()) ? "ENTERED" : "EXITED");
        s.setLastTransitionType(s.getLastGeofenceEventType());
        s.setLastTransitionTime(at);
        s.setLastEventAt(at);
        if (station != null) s.setCurrentStation(station);
        if (platform != null) s.setCurrentPlatform(platform);
        if ("GeofenceEntered".equals(e.getEventType())) {
            s.setJourneyStatus("AT_STATION");
        } else {
            s.setJourneyStatus("IN_TRANSIT");
        }

        if ("GeofenceExited".equals(e.getEventType()) && station != null) {
            s.setCandidateOriginStation(station);
            s.setCandidateOriginTime(s.getLastEventAt());
        }
        return s;
    }

    private static boolean isSegmentReady(PassengerMovementState s) {
        if (s == null) return false;
        if (!"ENTERED".equals(s.getLastGeofenceEventType())) return false;
        if (s.getCandidateOriginStation() == null || s.getCurrentStation() == null) return false;
        if (s.getCandidateOriginStation().equalsIgnoreCase(s.getCurrentStation())) return false;
        return true;
    }

    @SuppressWarnings("unchecked")
    private static String stationFromPayload(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) return null;
        Object station = map.get("stationName");
        return station != null ? String.valueOf(station) : null;
    }

    @SuppressWarnings("unchecked")
    private static String platformFromPayload(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) return null;
        Object platform = map.get("platform");
        return platform != null ? String.valueOf(platform) : null;
    }
}


package com.train.booking.movement.health;

import com.train.booking.movement.eventlog.MovementEventEntity;
import com.train.booking.movement.eventlog.MovementEventRepository;
import com.train.booking.movement.eventlog.MovementEventType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Freshness of station-layer movement facts (geofence enter/exit appended to movement log).
 */
@Component("stationProcessing")
public class StationProcessingHealthIndicator implements HealthIndicator {

    @Value("${booking.movement.stale-threshold-seconds:300}")
    private long staleThresholdSeconds;

    private final MovementEventRepository repository;

    public StationProcessingHealthIndicator(MovementEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        List<MovementEventEntity> latest = repository.findByEventTypeInOrderByRecordedAtDesc(
            List.of(MovementEventType.GeofenceEntered, MovementEventType.GeofenceExited),
            PageRequest.of(0, 1)
        );
        if (latest.isEmpty()) {
            return Health.unknown().withDetail("reason", "No station geofence movement events yet").build();
        }
        MovementEventEntity e = latest.get(0);
        Instant last = e.getRecordedAt();
        Instant now = Instant.now();
        long lag = Duration.between(last, now).getSeconds();
        if (lag > staleThresholdSeconds) {
            return Health.down()
                .withDetail("lastStationMovementAt", last)
                .withDetail("lagSeconds", lag)
                .withDetail("thresholdSeconds", staleThresholdSeconds)
                .build();
        }
        return Health.up()
            .withDetail("lastStationMovementAt", last)
            .withDetail("lagSeconds", lag)
            .build();
    }
}

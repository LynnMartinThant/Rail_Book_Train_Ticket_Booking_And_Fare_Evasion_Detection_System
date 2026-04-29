package com.train.booking.movement.health;

import com.train.booking.movement.eventlog.MovementEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component("movementPipeline")
public class MovementPipelineHealthIndicator implements HealthIndicator {

    @Value("${booking.movement.stale-threshold-seconds:300}")
    private long staleThresholdSeconds;

    private final MovementEventRepository repository;

    public MovementPipelineHealthIndicator(MovementEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        var latest = repository.findFirstByOrderByRecordedAtDesc();
        if (latest.isEmpty()) {
            return Health.unknown().withDetail("reason", "No movement events yet").build();
        }
        Instant now = Instant.now();
        Instant last = latest.get().getRecordedAt();
        long lag = Duration.between(last, now).getSeconds();
        if (lag > staleThresholdSeconds) {
            return Health.down()
                .withDetail("lastEventAt", last)
                .withDetail("lagSeconds", lag)
                .withDetail("thresholdSeconds", staleThresholdSeconds)
                .build();
        }
        return Health.up()
            .withDetail("lastEventAt", last)
            .withDetail("lagSeconds", lag)
            .build();
    }
}


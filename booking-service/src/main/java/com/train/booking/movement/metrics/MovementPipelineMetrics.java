package com.train.booking.movement.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MovementPipelineMetrics {

    private final MeterRegistry registry;
    private final Timer appendTimer;
    private final Timer policyTimer;

    public MovementPipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.appendTimer = Timer.builder("movement.events.append.duration")
            .description("Time spent appending movement events")
            .register(registry);
        this.policyTimer = Timer.builder("movement.policy.duration")
            .description("Time spent in movement policy handlers")
            .register(registry);
    }

    public void recordAppend(Duration d, String type) {
        recordAppend(d, type, "UNKNOWN");
    }

    public void recordAppend(Duration d, String type, String sourceLayer) {
        appendTimer.record(d);
        Counter.builder("movement.events.append.count")
            .tag("type", type != null ? type : "unknown")
            .tag("sourceLayer", sourceLayer != null ? sourceLayer : "unknown")
            .register(registry)
            .increment();
    }

    public void recordProjectionDelay(Duration d) {
        Timer.builder("movement.projection.delay")
            .description("Delay between occurredAt and projection handling")
            .register(registry)
            .record(d);
    }

    public void recordPolicy(Duration d, String stage) {
        policyTimer.record(d);
        Counter.builder("movement.policy.count")
            .tag("stage", stage != null ? stage : "unknown")
            .register(registry)
            .increment();
    }

    public void recordJourneySegmentConfirmed() {
        Counter.builder("movement.journey.segment.confirmed.count")
            .register(registry)
            .increment();
    }

    public void recordFareValidated() {
        Counter.builder("movement.fare.validated.count")
            .register(registry)
            .increment();
    }

    public void recordFraudDecision() {
        Counter.builder("movement.fraud.decision.count")
            .register(registry)
            .increment();
    }

    public void recordDuplicateSuppressed(String module) {
        Counter.builder("movement.duplicate.suppressed.count")
            .tag("module", module != null ? module : "unknown")
            .register(registry)
            .increment();
    }

    public void recordLocationAccepted() {
        Counter.builder("movement.location.accepted.count")
            .register(registry)
            .increment();
    }

    public void recordGeofenceTransition() {
        Counter.builder("movement.geofence.transition.count")
            .register(registry)
            .increment();
    }
}

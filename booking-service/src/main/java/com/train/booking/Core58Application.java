package com.train.booking;

import com.train.booking.api.Core58Controller;
import com.train.booking.config.DetectionRules;
import com.train.booking.config.GeofenceDataInitializer;
import com.train.booking.config.KafkaConfig;
import com.train.booking.config.RouteOrderConfig;
import com.train.booking.confidence.ConfidenceScoringService;
import com.train.booking.confidence.DefaultConfidenceScoringService;
import com.train.booking.decision.DeterministicSegmentDecisionService;
import com.train.booking.decision.SegmentDecisionService;
import com.train.booking.entitlement.DefaultEntitlementResolutionService;
import com.train.booking.entitlement.EntitlementResolutionService;
import com.train.booking.fare.FarePolicyRulesService;
import com.train.booking.movement.eventlog.MovementEventStream;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.movement.projection.PassengerMovementProjectionConsumer;
import com.train.booking.movement.snapshot.PassengerMovementSnapshotService;
import com.train.booking.movement.stream.JourneySegmentCommandService;
import com.train.booking.movement.stream.MovementJourneyFallbackConsumer;
import com.train.booking.platform.farepolicy.FarePolicyConsumer;
import com.train.booking.platform.fraudpolicy.FraudPolicyConsumer;
import com.train.booking.service.AuditLogService;
import com.train.booking.service.DisputeService;
import com.train.booking.service.FraudDetectionService;
import com.train.booking.service.GeofenceRulesService;
import com.train.booking.service.GeofenceService;
import com.train.booking.service.LocationEventStream;
import com.train.booking.service.LocationService;
import com.train.booking.service.SegmentStateMachine;
import com.train.booking.service.TripSegmentService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootConfiguration
@EnableAutoConfiguration
@EnableScheduling
@Profile("core58")
@EntityScan(basePackages = {
    "com.train.booking.domain",
    "com.train.booking.movement.eventlog",
    "com.train.booking.movement.projection",
    "com.train.booking.movement.snapshot"
})
@EnableJpaRepositories(basePackages = {
    "com.train.booking.repository",
    "com.train.booking.movement.eventlog",
    "com.train.booking.movement.projection",
    "com.train.booking.movement.snapshot"
})
@ComponentScan(
    useDefaultFilters = false,
    includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
        Core58Controller.class,
        DetectionRules.class,
        GeofenceDataInitializer.class,
        KafkaConfig.class,
        RouteOrderConfig.class,
        MovementEventStream.class,
        MovementEventWriter.class,
        MovementPipelineMetrics.class,
        JourneySegmentCommandService.class,
        MovementJourneyFallbackConsumer.class,
        PassengerMovementProjectionConsumer.class,
        PassengerMovementSnapshotService.class,
        FarePolicyConsumer.class,
        FraudPolicyConsumer.class,
        AuditLogService.class,
        DisputeService.class,
        FraudDetectionService.class,
        GeofenceRulesService.class,
        GeofenceService.class,
        LocationEventStream.class,
        LocationService.class,
        SegmentStateMachine.class,
        TripSegmentService.class,
        DefaultConfidenceScoringService.class,
        ConfidenceScoringService.class,
        DeterministicSegmentDecisionService.class,
        SegmentDecisionService.class,
        DefaultEntitlementResolutionService.class,
        EntitlementResolutionService.class,
        FarePolicyRulesService.class
    })
)
public class Core58Application {
}

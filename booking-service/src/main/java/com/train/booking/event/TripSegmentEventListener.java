package com.train.booking.event;

import com.train.booking.domain.FareStatus;
import com.train.booking.domain.TripSegment;
import com.train.booking.service.TripSegmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consumes TripSegmentCreatedEvent (event-driven architecture).
 * In a full system this would publish to Kafka/RabbitMQ for Fare Service, Billing Service, Audit Service.
 */
@Component
@Slf4j
public class TripSegmentEventListener {

    @EventListener
    public void onTripSegmentCreated(TripSegmentService.TripSegmentCreatedEvent event) {
        TripSegment segment = event.getSegment();
        log.info("EVENT TripSegmentCreated: passenger={} {}→{} fareStatus={}",
            segment.getPassengerId(), segment.getOriginStation(), segment.getDestinationStation(), segment.getFareStatus());
        if (segment.getFareStatus() == FareStatus.UNPAID_TRAVEL) {
            log.warn("EVENT PenaltyIssued: passenger={} penalty={}", segment.getPassengerId(), segment.getPenaltyAmount());
        } else if (segment.getAdditionalFare() != null && segment.getAdditionalFare().signum() > 0) {
            log.info("EVENT FareRecalculated: passenger={} additionalFare={}", segment.getPassengerId(), segment.getAdditionalFare());
        }
    }
}

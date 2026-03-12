package com.train.booking.service;

import com.train.booking.domain.FareBucket;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.Trip;
import com.train.booking.repository.FareBucketRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TripSeatRepository;
import com.train.booking.rules.PricingRuleFacts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic pricing using fare buckets and Drools. Returns the best available fare
 * for a trip given booking time, seat availability, and peak/off-peak rules.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/London");
    private static final BigDecimal WEEKEND_OFFPEAK_DISCOUNT_PCT = new BigDecimal("0.05");

    private final FareBucketRepository fareBucketRepository;
    private final ReservationRepository reservationRepository;
    private final TripSeatRepository tripSeatRepository;

    /**
     * Returns the price and tier name for a trip at the given booking time.
     * If no fare buckets exist or Drools fails, falls back to trip.getPricePerSeat().
     */
    public PricingResult getPrice(Trip trip, Instant bookingTime) {
        List<FareBucket> buckets = fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(trip.getId());
        if (buckets.isEmpty()) {
            return new PricingResult(trip.getPricePerSeat(), "STANDARD", false);
        }

        int totalSeats = tripSeatRepository.countByTripId(trip.getId());
        if (totalSeats == 0) {
            return new PricingResult(trip.getPricePerSeat(), "STANDARD", false);
        }

        long seatsSold = reservationRepository.countByTripIdAndStatusIn(
            trip.getId(),
            List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID)
        );
        double occupancyPct = totalSeats > 0 ? (seatsSold * 100.0 / totalSeats) : 0;

        ZonedDateTime depZ = ZonedDateTime.ofInstant(trip.getDepartureTime(), DEFAULT_ZONE);
        ZonedDateTime bookZ = ZonedDateTime.ofInstant(bookingTime, DEFAULT_ZONE);
        boolean peak = isPeak(depZ);
        boolean weekend = depZ.getDayOfWeek() == DayOfWeek.SATURDAY || depZ.getDayOfWeek() == DayOfWeek.SUNDAY;
        long daysUntil = java.time.temporal.ChronoUnit.DAYS.between(bookZ.toLocalDate(), depZ.toLocalDate());

        PricingRuleFacts.PricingContext ctx = new PricingRuleFacts.PricingContext(
            trip.getId(),
            trip.getFromStation(),
            trip.getToStation(),
            trip.getDepartureTime(),
            bookingTime,
            totalSeats,
            (int) seatsSold,
            occupancyPct,
            peak,
            weekend,
            daysUntil
        );

        int cumulative = 0;
        List<PricingRuleFacts.FareTierOption> options = new ArrayList<>();
        for (FareBucket b : buckets) {
            boolean availableByAllocation;
            if (b.getSeatsAllocated() < 0) {
                cumulative = -1;
                availableByAllocation = true;
            } else {
                cumulative += b.getSeatsAllocated();
                availableByAllocation = seatsSold < cumulative;
            }
            options.add(new PricingRuleFacts.FareTierOption(
                b.getTierName(),
                b.getPrice(),
                b.getDisplayOrder(),
                availableByAllocation
            ));
        }

        try {
            byte[] drlBytes = new ClassPathResource("rules/pricing.drl").getInputStream().readAllBytes();
            String drl = new String(drlBytes, StandardCharsets.UTF_8);
            KieServices ks = KieServices.Factory.get();
            KieFileSystem kfs = ks.newKieFileSystem();
            kfs.write("src/main/resources/rules/pricing.drl", ks.getResources().newByteArrayResource(drlBytes).setResourceType(ResourceType.DRL));
            KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
            if (kb.getResults().hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
                log.warn("Pricing Drools build errors: {}", kb.getResults().toString());
                return fallback(trip, options);
            }
            KieModule km = kb.getKieModule();
            KieContainer kc = ks.newKieContainer(km.getReleaseId());
            KieSession session = kc.getKieBase().newKieSession();
            try {
                session.insert(ctx);
                options.forEach(session::insert);
                session.fireAllRules();
                PricingRuleFacts.SelectedFare selected = selectLowestAvailable(options);
                if (selected == null) {
                    return fallback(trip, options);
                }
                BigDecimal price = selected.getPrice();
                if (weekend && "OFF_PEAK".equals(selected.getTierName())) {
                    price = price.multiply(BigDecimal.ONE.subtract(WEEKEND_OFFPEAK_DISCOUNT_PCT)).setScale(2, RoundingMode.HALF_UP);
                }
                return new PricingResult(price, selected.getTierName(), true);
            } finally {
                session.dispose();
            }
        } catch (Exception e) {
            log.warn("Pricing Drools failed, using fallback: {}", e.getMessage());
            return fallback(trip, options);
        }
    }

    private static boolean isPeak(ZonedDateTime departure) {
        int hour = departure.getHour();
        int minute = departure.getMinute();
        int totalMins = hour * 60 + minute;
        // 06:30 – 09:30
        if (totalMins >= 6 * 60 + 30 && totalMins < 9 * 60 + 30) return true;
        // 16:00 – 19:00
        if (totalMins >= 16 * 60 && totalMins < 19 * 60) return true;
        return false;
    }

    private static PricingRuleFacts.SelectedFare selectLowestAvailable(List<PricingRuleFacts.FareTierOption> options) {
        return options.stream()
            .filter(PricingRuleFacts.FareTierOption::isAvailable)
            .min((a, b) -> a.getPrice().compareTo(b.getPrice()))
            .map(o -> new PricingRuleFacts.SelectedFare(o.getPrice(), o.getTierName()))
            .orElse(null);
    }

    private static PricingResult fallback(Trip trip, List<PricingRuleFacts.FareTierOption> options) {
        PricingRuleFacts.SelectedFare s = selectLowestAvailable(options);
        if (s != null) {
            return new PricingResult(s.getPrice(), s.getTierName(), true);
        }
        return new PricingResult(trip.getPricePerSeat(), "STANDARD", false);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PricingResult {
        private final BigDecimal price;
        private final String tierName;
        private final boolean dynamic;
    }
}

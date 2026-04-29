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
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

// Dynamic pricing using drools and fare buckets
@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/London");
    private static final BigDecimal WEEKEND_OFFPEAK_DISCOUNT_PCT = new BigDecimal("0.05");
    private static final String PRICING_DRL_PATH = "com/train/booking/rules/pricing.drl";

    private final FareBucketRepository fareBucketRepository;
    private final ReservationRepository reservationRepository;
    private final TripSeatRepository tripSeatRepository;

    private volatile KieContainer pricingContainer;

    private KieContainer getOrBuildPricingContainer() {
        if (pricingContainer != null) return pricingContainer;
        synchronized (this) {
            if (pricingContainer != null) return pricingContainer;
            try {
                byte[] drlBytes = new ClassPathResource(PRICING_DRL_PATH).getInputStream().readAllBytes();
                String drlContent = new String(drlBytes, java.nio.charset.StandardCharsets.UTF_8); // read all the bytes in drl to run pricing rules
                KieServices ks = KieServices.Factory.get();
                KieFileSystem kfs = ks.newKieFileSystem();
                kfs.write("src/main/resources/" + PRICING_DRL_PATH, drlContent);
                KieBuilder kb = ks.newKieBuilder(kfs).buildAll();
                if (kb.getResults().hasMessages(org.kie.api.builder.Message.Level.ERROR)) {
                    log.warn("Pricing Drools build errors: {}", kb.getResults().toString());
                    return null;
                }
                KieModule km = kb.getKieModule();
                pricingContainer = ks.newKieContainer(km.getReleaseId());
                log.info("Pricing Drools container built successfully");
                return pricingContainer;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("Pricing Drools init failed: {} — {}", msg, e.getClass().getName(), e);
                return null;
            }
        }
    }


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
        double occupancyPct = totalSeats > 0 ? (seatsSold * 100.0 / totalSeats) : 0; //OCCUPANCY

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

        KieContainer kc = getOrBuildPricingContainer();
        if (kc == null) {
            return fallback(trip, options);
        }
        try {
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
                log.info("Pricing via Drools: tripId={} tier={} price={}", trip.getId(), selected.getTierName(), price);
                return new PricingResult(price, selected.getTierName(), true);
            } finally {
                session.dispose();
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Pricing Drools failed, using fallback: {} — {}", msg, e.getClass().getName(), e);
            return fallback(trip, options);
        }
    }

    private static boolean isPeak(ZonedDateTime departure) { // peak time zone
        int hour = departure.getHour();
        int minute = departure.getMinute();
        int totalMins = hour * 60 + minute;
    
        if (totalMins >= 6 * 60 + 30 && totalMins < 9 * 60 + 30) return true;
       
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

   // verification of drools *
    public DroolsStatus getDroolsStatus() {
        KieContainer kc = getOrBuildPricingContainer();
        if (kc == null) {
            return new DroolsStatus("unavailable", "Pricing container failed to build (see logs).");
        }
        try {
            KieSession session = kc.getKieBase().newKieSession();
            try {
                Instant now = Instant.now();
                PricingRuleFacts.PricingContext ctx = new PricingRuleFacts.PricingContext(
                    0L, "A", "B", now, now, 100, 0, 0.0, false, false, 7L);
                session.insert(ctx);
                session.insert(new PricingRuleFacts.FareTierOption("ADVANCE_1", BigDecimal.TEN, 0, true));
                session.fireAllRules();
            } finally {
                session.dispose();
            }
            return new DroolsStatus("ok", "Pricing rules loaded and executed successfully.");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new DroolsStatus("unavailable", "Rule execution failed: " + msg);
        }
    }
// get status
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class DroolsStatus {
        private final String status;  
        private final String message;
    }
//return
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PricingResult {
        private final BigDecimal price;
        private final String tierName;
        private final boolean dynamic;
    }
}

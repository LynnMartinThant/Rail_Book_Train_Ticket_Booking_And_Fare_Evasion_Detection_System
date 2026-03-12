package com.train.booking.config;

import com.train.booking.domain.FareBucket;
import com.train.booking.domain.Trip;
import com.train.booking.repository.FareBucketRepository;
import com.train.booking.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds default fare buckets (Advance 1–3, Off-Peak, Anytime) for each trip when none exist.
 * Runs after DataInitializer so trips are present.
 */
@Component
@Profile("!test")
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class FareBucketInitializer implements CommandLineRunner {

    private final TripRepository tripRepository;
    private final FareBucketRepository fareBucketRepository;

    /** Default tiers: Advance 1–3 (limited seats), Off-Peak and Anytime (unlimited). Train has 20 seats. */
    private static final Object[][] DEFAULT_TIERS = {
        {"ADVANCE_1", 5, "8.00", 1},
        {"ADVANCE_2", 5, "12.00", 2},
        {"ADVANCE_3", 5, "16.00", 3},
        {"OFF_PEAK", -1, "18.00", 4},
        {"ANYTIME", -1, "24.00", 5},
    };

    @Override
    @Transactional
    public void run(String... args) {
        List<Trip> trips = tripRepository.findAllWithTrain();
        int added = 0;
        for (Trip trip : trips) {
            if (!fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(trip.getId()).isEmpty()) {
                continue;
            }
            for (Object[] row : DEFAULT_TIERS) {
                FareBucket b = FareBucket.builder()
                    .trip(trip)
                    .tierName((String) row[0])
                    .seatsAllocated((Integer) row[1])
                    .price(new BigDecimal((String) row[2]))
                    .displayOrder((Integer) row[3])
                    .build();
                fareBucketRepository.save(b);
                added++;
            }
        }
        if (added > 0) {
            log.info("Fare bucket seed: {} buckets for {} trips", added, added / DEFAULT_TIERS.length);
        }
    }
}

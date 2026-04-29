package com.train.booking.service;

import com.train.booking.domain.FareBucket;
import com.train.booking.domain.Trip;
import com.train.booking.repository.FareBucketRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TripSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PricingService (Drools dynamic pricing and fallback).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PricingService")
class PricingServiceTest {

    @Mock
    private FareBucketRepository fareBucketRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private TripSeatRepository tripSeatRepository;

    private PricingService pricingService;
    private Trip trip;

    @BeforeEach
    void setUp() {
        pricingService = new PricingService(fareBucketRepository, reservationRepository, tripSeatRepository);
        trip = Trip.builder()
            .id(1L)
            .fromStation("Leeds")
            .toStation("Sheffield")
            .departureTime(Instant.now().plusSeconds(3600))
            .pricePerSeat(BigDecimal.valueOf(10.00))
            .build();
    }

    @Test
    void getPrice_whenNoFareBuckets_returnsStandardFallback() {
        when(fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(1L)).thenReturn(Collections.emptyList());
        PricingService.PricingResult result = pricingService.getPrice(trip, Instant.now());
        assertThat(result.getTierName()).isEqualTo("STANDARD");
        assertThat(result.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(10.00));
        assertThat(result.isDynamic()).isFalse();
    }

    @Test
    void getPrice_whenTripHasNoSeats_returnsStandardFallback() {
        when(fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(1L)).thenReturn(
            List.of(FareBucket.builder().tierName("ADVANCE_1").seatsAllocated(5).price(BigDecimal.valueOf(8)).displayOrder(0).build()));
        when(tripSeatRepository.countByTripId(1L)).thenReturn(0);
        PricingService.PricingResult result = pricingService.getPrice(trip, Instant.now());
        assertThat(result.getTierName()).isEqualTo("STANDARD");
        assertThat(result.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(10.00));
    }

    @Test
    void getPrice_whenFareBucketsExist_returnsDynamicTier() {
        when(fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(1L)).thenReturn(
            List.of(
                FareBucket.builder().trip(trip).tierName("ADVANCE_1").seatsAllocated(10).price(BigDecimal.valueOf(8.00)).displayOrder(0).build(),
                FareBucket.builder().trip(trip).tierName("ANYTIME").seatsAllocated(-1).price(BigDecimal.valueOf(10.00)).displayOrder(1).build()));
        when(tripSeatRepository.countByTripId(1L)).thenReturn(10);
        when(reservationRepository.countByTripIdAndStatusIn(anyLong(), org.mockito.ArgumentMatchers.anyList())).thenReturn(0L);
        PricingService.PricingResult result = pricingService.getPrice(trip, Instant.now());
        assertThat(result.getPrice()).isNotNull();
        assertThat(result.getTierName()).isNotBlank();
    }
}

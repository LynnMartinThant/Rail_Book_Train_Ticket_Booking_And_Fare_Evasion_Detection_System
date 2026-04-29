package com.train.booking.memory;

import com.train.booking.domain.FareStatus;
import com.train.booking.domain.Geofence;
import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.TripSegment;
import com.train.booking.domain.UserLocation;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick verification for agreed plan Step 2 before Layer 1 (ingestion).
 */
class InMemoryStoresStep2Test {

    @Test
    void userLocation_crudAndUniqueUserId() {
        InMemoryUserLocationStore store = new InMemoryUserLocationStore();
        UserLocation a = UserLocation.builder().userId("u1").latitude(1.0).longitude(2.0).build();
        store.save(a);
        assertThat(a.getId()).isNotNull();
        assertThat(store.findByUserId("u1")).isPresent();
        a.setLatitude(3.0);
        store.save(a);
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findByUserId("u1").orElseThrow().getLatitude()).isEqualTo(3.0);
        store.deleteById(a.getId());
        assertThat(store.count()).isZero();
    }

    @Test
    void geofence_crudAndQueries() {
        InMemoryGeofenceStore store = new InMemoryGeofenceStore();
        Geofence g = Geofence.builder()
                .name("B Station")
                .stationName("B")
                .latitude(53.0).longitude(-1.5)
                .radiusMeters(120)
                .build();
        store.save(g);
        assertThat(store.findByStationName("b")).isPresent();
        assertThat(store.findAllByOrderByNameAsc().get(0).getName()).isEqualTo("B Station");
        store.deleteById(g.getId());
        assertThat(store.count()).isZero();
    }

    @Test
    void reservation_crud() {
        InMemoryReservationStore store = new InMemoryReservationStore();
        Reservation r = Reservation.builder()
                .userId("u1")
                .status(ReservationStatus.CONFIRMED)
                .amount(BigDecimal.TEN)
                .build();
        store.save(r);
        assertThat(store.findByIdAndUserId(r.getId(), "u1")).isPresent();
        assertThat(store.findByUserIdOrderByCreatedAtDesc("u1")).hasSize(1);
        store.deleteById(r.getId());
        assertThat(store.count()).isZero();
    }

    @Test
    void tripSegment_idempotencyUpsert() {
        InMemoryTripSegmentStore store = new InMemoryTripSegmentStore();
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t1 = Instant.parse("2026-01-01T11:00:00Z");
        TripSegment s1 = TripSegment.builder()
                .passengerId("p1")
                .originStation("A").destinationStation("B")
                .segmentStartTime(t0).segmentEndTime(t1)
                .fareStatus(FareStatus.PAID)
                .idempotencyKey("key-1")
                .build();
        store.save(s1);
        Long id = s1.getId();
        s1.setFareStatus(FareStatus.UNDERPAID);
        store.save(s1);
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.findByIdempotencyKey("key-1").orElseThrow().getFareStatus()).isEqualTo(FareStatus.UNDERPAID);
        assertThat(store.findByPassengerIdOrderByCreatedAtDesc("p1", PageRequest.of(0, 5))).hasSize(1);
        assertThat(store.findById(id).orElseThrow().getId()).isEqualTo(id);
        store.deleteAll();
        assertThat(store.count()).isZero();
    }
}

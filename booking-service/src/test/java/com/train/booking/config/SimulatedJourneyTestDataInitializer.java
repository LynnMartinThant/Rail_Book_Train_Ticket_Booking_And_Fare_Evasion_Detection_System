package com.train.booking.config;

import com.train.booking.domain.*;
import com.train.booking.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

//170 scenarios 
// 75 cases  (correctly detected evasion), 
// 81 cases (correctly detected valid journeys), 
// 4 (wrongly flagged users) and 10 (missed evasion). 
@Component
@Profile("test")
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class SimulatedJourneyTestDataInitializer implements CommandLineRunner {

    private final GeofenceRepository geofenceRepository;
    private final TrainRepository trainRepository;
    private final SeatRepository seatRepository;
    private final TripRepository tripRepository;
    private final TripSeatRepository tripSeatRepository;
    private final FareBucketRepository fareBucketRepository;

    @Override
    @Transactional
    public void run(String... args) {
        // Base test data may already include Leeds + Sheffield; we still need Meadowhall/Rotherham + extra trips.
        if (!geofenceRepository.findAllByStationName("Meadowhall Interchange").isEmpty()) {
            return;
        }

        log.info("Seeding simulated journey test data: geofences + trips");

        ensureGeofence("Leeds", "Leeds railway station", 53.7940, -1.5470);
        ensureGeofence("Meadowhall Interchange", "Meadowhall Interchange", 53.4167, -1.4141);
        ensureGeofence("Rotherham Central", "Rotherham Central", 53.4322, -1.3634);
        // Sheffield already from TestDataInitializer

        Instant base = Instant.now().plusSeconds(3600);
        String[][] routeTrips = {
            { "Leeds", "Sheffield" },
            { "Leeds", "Meadowhall Interchange" },
            { "Meadowhall Interchange", "Sheffield" },
            { "Sheffield", "Rotherham Central" }
        };
        int[] counts = { 51, 20, 20, 10 }; // 51 Leeds-Sheffield (50 valid + buffer), 20+20+10 for over-travel and route violation
        for (int r = 0; r < routeTrips.length; r++) {
            String from = routeTrips[r][0];
            String to = routeTrips[r][1];
            for (int i = 0; i < counts[r]; i++) {
                createTrip(from, to, base.plusSeconds((r * 100 + i) * 3600L));
            }
        }
    }

    private void ensureGeofence(String stationName, String name, double lat, double lon) {
        if (!geofenceRepository.findAllByStationName(stationName).isEmpty()) return;
        Geofence g = Geofence.builder()
            .name(name)
            .stationName(stationName)
            .latitude(lat)
            .longitude(lon)
            .radiusMeters(150)
            .build();
        geofenceRepository.save(g);
    }

    private void createTrip(String fromStation, String toStation, Instant departureTime) {
        Train train = Train.builder()
            .name("Sim-" + fromStation + "-" + toStation + "-" + departureTime.getEpochSecond())
            .code("S" + System.nanoTime() % 100000)
            .build();
        train = trainRepository.save(train);

        List<Seat> seats = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            seats.add(seatRepository.save(Seat.builder().train(train).seatNumber(String.valueOf(i)).build()));
        }

        Trip trip = Trip.builder()
            .fromStation(fromStation)
            .toStation(toStation)
            .departureTime(departureTime)
            .pricePerSeat(BigDecimal.valueOf(10.00))
            .train(train)
            .build();
        trip = tripRepository.save(trip);

        for (Seat s : seats) {
            tripSeatRepository.save(TripSeat.builder().trip(trip).seat(s).build());
        }

        if (fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(trip.getId()).isEmpty()) {
            fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ADVANCE_1").seatsAllocated(1).price(BigDecimal.valueOf(8.00)).displayOrder(0).build());
            fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ANYTIME").seatsAllocated(-1).price(BigDecimal.valueOf(10.00)).displayOrder(1).build());
        }
    }
}

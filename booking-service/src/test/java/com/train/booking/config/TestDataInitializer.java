package com.train.booking.config;

import com.train.booking.domain.*;
import com.train.booking.repository.*;
import lombok.RequiredArgsConstructor; 
import org.springframework.boot.CommandLineRunner; // cmd 
import org.springframework.context.annotation.Profile; //user pf
import org.springframework.core.annotation.Order; 
import org.springframework.stereotype.Component; // component
import org.springframework.transaction.annotation.Transactional; // service

import java.math.BigDecimal;
import java.time.Instant;


// minimal data for integration tests 
@Component
@Profile("test")
@Order(1)
@RequiredArgsConstructor
public class TestDataInitializer implements CommandLineRunner {

    private final GeofenceRepository geofenceRepository;
    private final TrainRepository trainRepository;
    private final SeatRepository seatRepository;
    private final TripRepository tripRepository;
    private final TripSeatRepository tripSeatRepository;
    private final FareBucketRepository fareBucketRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (geofenceRepository.count() > 0) return;

        Geofence leeds = Geofence.builder() // geofence for test
            .name("Leeds Station")
            .stationName("Leeds")
            .latitude(53.796)
            .longitude(-1.547)
            .radiusMeters(150)
            .build();
        geofenceRepository.save(leeds);

        Geofence geofence = Geofence.builder()
            .name("Sheffield Station")
            .stationName("Sheffield")
            .latitude(53.38)
            .longitude(-1.47)
            .radiusMeters(150)
            .build();
        geofenceRepository.save(geofence);

        Train train = Train.builder()
            .name("Northern Hallam Test")
            .code("NHT")
            .build();
        train = trainRepository.save(train);

        Seat seat1 = Seat.builder().train(train).seatNumber("1").build(); //seat for test
        Seat seat2 = Seat.builder().train(train).seatNumber("2").build();
        Seat seat3 = Seat.builder().train(train).seatNumber("3").build();
        Seat seat4 = Seat.builder().train(train).seatNumber("4").build();
        seat1 = seatRepository.save(seat1);
        seat2 = seatRepository.save(seat2);
        seat3 = seatRepository.save(seat3);
        seat4 = seatRepository.save(seat4);

        Trip trip = Trip.builder()
            .fromStation("Leeds")
            .toStation("Sheffield")
            .departureTime(Instant.now().plusSeconds(3600))
            .pricePerSeat(BigDecimal.valueOf(10.00))
            .train(train)
            .build();
        trip = tripRepository.save(trip);

        tripSeatRepository.save(TripSeat.builder().trip(trip).seat(seat1).build()); // trip for test
        tripSeatRepository.save(TripSeat.builder().trip(trip).seat(seat2).build());
        tripSeatRepository.save(TripSeat.builder().trip(trip).seat(seat3).build());
        tripSeatRepository.save(TripSeat.builder().trip(trip).seat(seat4).build());

        if (fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(trip.getId()).isEmpty()) {
            fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ADVANCE_1").seatsAllocated(1).price(BigDecimal.valueOf(8.00)).displayOrder(0).build());
            fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ADVANCE_2").seatsAllocated(1).price(BigDecimal.valueOf(9.00)).displayOrder(1).build());
            fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ANYTIME").seatsAllocated(-1).price(BigDecimal.valueOf(10.00)).displayOrder(2).build());
        }
    }
}

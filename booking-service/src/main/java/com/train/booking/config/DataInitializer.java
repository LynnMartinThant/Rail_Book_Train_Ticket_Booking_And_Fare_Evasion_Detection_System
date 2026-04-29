package com.train.booking.config;

import com.train.booking.domain.*;
import com.train.booking.repository.FareBucketRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TrainRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.repository.TripSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("!test")
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private static final int SEATS_PER_TRAIN = 20;
    private static final String[] PLATFORMS = {"1A", "1B", "2A", "2B", "3A", "3B", "4A", "4B", "5A", "5B"};

    /** Per-line: from, to, train name prefix, code prefix, number of trains, departure minutes, prices. */
    private static final Object[][] LINES = {
        // Hallam: Leeds → Sheffield
        {"Leeds", "Sheffield", "Northern Hallam", "NH", 4, new int[]{2, 5, 11, 16, 20, 25, 30, 35, 40, 45, 50, 55}, new String[]{"2.80", "2.80", "3.20", "2.80", "3.20", "2.80", "2.80", "3.20", "2.80", "2.80", "3.20", "2.80"}},
        // Calder Valley: Leeds → Manchester Victoria
        {"Leeds", "Manchester Victoria", "Northern Calder", "NC", 2, new int[]{10, 25, 40, 55}, new String[]{"12.50", "12.50", "14.00", "12.50"}},
        // Hope Valley: Sheffield → Manchester Piccadilly
        {"Sheffield", "Manchester Piccadilly", "Northern Hope Valley", "NHV", 2, new int[]{15, 35, 55}, new String[]{"15.00", "15.00", "17.00"}},
        // Leeds → Manchester Victoria via Huddersfield
        {"Leeds", "Manchester Victoria", "Northern Huddersfield", "NHUD", 2, new int[]{8, 38}, new String[]{"13.00", "13.00"}},
        // Sheffield → Leeds via Wakefield
        {"Sheffield", "Leeds", "Northern Wakefield", "NW", 2, new int[]{12, 32, 52}, new String[]{"8.50", "8.50", "9.20"}},
    };

    @Value("${booking.data.reset-on-startup:false}")
    private boolean resetOnStartup;

    @PersistenceContext
    private EntityManager entityManager;

    private final TrainRepository trainRepository;
    private final TripRepository tripRepository;
    private final TripSeatRepository tripSeatRepository;
    private final ReservationRepository reservationRepository;
    private final FareBucketRepository fareBucketRepository;

    @Override
    @Transactional
    public void run(String... args) {
        if (resetOnStartup) {
            log.info("Resetting booking data (booking.data.reset-on-startup=true)");
            reservationRepository.deleteAllReservations(); // bulk DELETE so it runs before trip_seats (FK)
            entityManager.flush();
            fareBucketRepository.deleteAll();
            entityManager.flush();
            tripSeatRepository.deleteAll();
            entityManager.flush();
            tripRepository.deleteAll();
            trainRepository.deleteAll();
        }

        // Ensure we have 12 trains (4 Hallam + 2 per other line), create by slot so names match
        int totalNeeded = 0;
        for (Object[] l : LINES) totalNeeded += (Integer) l[4];
        List<Train> allTrains = new ArrayList<>(trainRepository.findAll());
        for (int slot = allTrains.size(); slot < totalNeeded; slot++) {
            int offset = 0;
            String namePrefix = null;
            String codePrefix = null;
            int nInLine = 0;
            for (Object[] line : LINES) {
                int numTrains = (Integer) line[4];
                if (slot < offset + numTrains) {
                    namePrefix = (String) line[2];
                    codePrefix = (String) line[3];
                    nInLine = slot - offset;
                    break;
                }
                offset += numTrains;
            }
            Train train = Train.builder()
                .name(namePrefix + " " + (nInLine + 1))
                .code(codePrefix + (100 + slot))
                .build();
            train = trainRepository.save(train);
            for (int s = 1; s <= SEATS_PER_TRAIN; s++) {
                Seat seat = Seat.builder().seatNumber("S" + s).train(train).build();
                train.getSeats().add(seat);
            }
            trainRepository.save(train);
            allTrains.add(train);
        }
        allTrains = new ArrayList<>(trainRepository.findAll());
        int trainOffset = 0;
        ZonedDateTime base = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).plusDays(1).plusHours(16);
        for (Object[] line : LINES) {
            String from = (String) line[0];
            String to = (String) line[1];
            String namePrefix = (String) line[2];
            int numTrains = (Integer) line[4];
            int[] mins = (int[]) line[5];
            String[] prices = (String[]) line[6];
            if (!tripRepository.findByFromStationAndToStation(from, to).isEmpty()) {
                trainOffset += numTrains;
                continue;
            }
            log.info("Adding {} → {} trips ({} departures)", from, to, mins.length);
            int end = Math.min(trainOffset + numTrains, allTrains.size());
            if (trainOffset >= end) { trainOffset += numTrains; continue; }
            List<Train> lineTrains = allTrains.subList(trainOffset, end);
            List<Trip> newTrips = new ArrayList<>();
            for (int i = 0; i < mins.length; i++) {
                Train train = lineTrains.get(i % lineTrains.size());
                BigDecimal price = new BigDecimal(prices[i % prices.length]);
                String platform = PLATFORMS[(trainOffset + i) % PLATFORMS.length];
                Instant dep = base.plusMinutes(mins[i]).toInstant();
                Trip trip = Trip.builder()
                    .train(train)
                    .fromStation(from)
                    .toStation(to)
                    .departureTime(dep)
                    .platform(platform)
                    .pricePerSeat(price)
                    .build();
                trip = tripRepository.save(trip);
                newTrips.add(trip);
            }
            for (Trip trip : newTrips) {
                Train train = trip.getTrain();
                List<Seat> seats = train.getSeats();
                if (seats.isEmpty()) seats = trainRepository.findById(train.getId()).orElseThrow().getSeats();
                for (Seat seat : seats) {
                    tripSeatRepository.save(TripSeat.builder().trip(trip).seat(seat).build());
                }
            }
            for (Trip trip : newTrips) {
                addFareBucketsForTrip(trip);
            }
            trainOffset += numTrains;
        }
        if (tripSeatRepository.count() == 0) {
            List<Trip> allTrips = tripRepository.findAllWithTrain();
            for (Trip trip : allTrips) {
                Train train = trip.getTrain();
                List<Seat> seats = train.getSeats();
                if (seats.isEmpty()) seats = trainRepository.findById(train.getId()).orElseThrow().getSeats();
                for (Seat seat : seats) {
                    tripSeatRepository.save(TripSeat.builder().trip(trip).seat(seat).build());
                }
            }
        }
        // Ensure all existing trips have fare buckets for dynamic pricing (Drools)
        List<Trip> allTrips = tripRepository.findAllWithTrain();
        for (Trip trip : allTrips) {
            if (fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(trip.getId()).isEmpty()) {
                addFareBucketsForTrip(trip);
            }
        }
        log.info("Data init done: {} trains, {} trips (all lines)", trainRepository.count(), tripRepository.count());
    }

    /**
     * Add four fare tiers so Drools dynamic pricing can apply: ADVANCE_1 (cheapest, limited),
     * ADVANCE_2, OFF_PEAK (disabled in peak hours by rule), ANYTIME (always available).
     */
    private void addFareBucketsForTrip(Trip trip) {
        java.math.BigDecimal base = trip.getPricePerSeat();
        if (base == null) base = java.math.BigDecimal.valueOf(10);
        fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ADVANCE_1")
            .price(base.multiply(java.math.BigDecimal.valueOf(0.5)).setScale(2, java.math.RoundingMode.HALF_UP))
            .seatsAllocated(5).displayOrder(0).build());
        fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ADVANCE_2")
            .price(base.multiply(java.math.BigDecimal.valueOf(0.75)).setScale(2, java.math.RoundingMode.HALF_UP))
            .seatsAllocated(8).displayOrder(1).build());
        fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("OFF_PEAK")
            .price(base.multiply(java.math.BigDecimal.valueOf(0.9)).setScale(2, java.math.RoundingMode.HALF_UP))
            .seatsAllocated(-1).displayOrder(2).build());
        fareBucketRepository.save(FareBucket.builder().trip(trip).tierName("ANYTIME")
            .price(base.setScale(2, java.math.RoundingMode.HALF_UP))
            .seatsAllocated(-1).displayOrder(3).build());
    }
}

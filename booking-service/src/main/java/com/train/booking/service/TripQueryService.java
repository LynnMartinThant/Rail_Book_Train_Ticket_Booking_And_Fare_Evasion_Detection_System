package com.train.booking.service;

import com.train.booking.config.RouteConfig;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.Trip;
import com.train.booking.domain.TripSeat;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.TripRepository;
import com.train.booking.repository.TripSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TripQueryService {

    private final TripRepository tripRepository;
    private final TripSeatRepository tripSeatRepository;
    private final ReservationRepository reservationRepository;
    private final RouteConfig routeConfig;

    public List<Trip> listTrips() {
        return tripRepository.findAllWithTrain();
    }

   
    public List<Trip> listTrips(String fromStation, String toStation) { // return all the trips
        if (fromStation != null && !fromStation.isBlank() && toStation != null && !toStation.isBlank()) {
            String from = fromStation.trim();
            String to = toStation.trim(); 
            List<Trip> exact = tripRepository.findByFromStationAndToStation(from, to); // find if match 
            if (!exact.isEmpty()) return exact;
            
            List<RouteConfig.EndpointPair> pairs = routeConfig.endpointPairsForSegment(from, to); 
            Set<Trip> merged = new LinkedHashSet<>(); // combine all the trip
            for (RouteConfig.EndpointPair p : pairs) {
                merged.addAll(tripRepository.findByFromStationAndToStation(p.from(), p.to()));
            }
            return merged.stream().sorted(Comparator.comparing(Trip::getDepartureTime)).toList();
        }
      
        List<Trip> hallam = tripRepository.findByFromStationAndToStation("Leeds", "Sheffield");
        return hallam.isEmpty() ? tripRepository.findAllWithTrain() : hallam;
    }

    
    public List<Trip> listDeparturesFromStation(String stationName) {
        return tripRepository.findByFromStationAndDepartureTimeAfterOrderByDepartureTimeAsc(stationName, Instant.now());
    }

    public List<TripSeat> getSeatsForTrip(Long tripId) {
        return tripSeatRepository.findByTripId(tripId);
    }

    public List<Long> getBookedSeatIdsForTrip(Long tripId) { // get all the bookes seats for trip
        List<TripSeat> seats = tripSeatRepository.findByTripId(tripId);
        Instant now = Instant.now();
        return seats.stream()
            .filter(ts -> reservationRepository.findActiveByTripSeatId(
                ts.getId(),
                List.of(ReservationStatus.RESERVED, ReservationStatus.PENDING_PAYMENT, ReservationStatus.PAYMENT_PROCESSING, ReservationStatus.PAID, ReservationStatus.CONFIRMED),
                now
            ).isPresent())
            .map(ts -> ts.getSeat().getId())
            .collect(Collectors.toList()); // return all the booked seat
    }
}

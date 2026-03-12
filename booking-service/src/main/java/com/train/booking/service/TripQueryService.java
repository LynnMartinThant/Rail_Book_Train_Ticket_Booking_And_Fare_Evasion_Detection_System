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

    /** List trips for segment fromStation → toStation. Returns trips that serve that segment (any station pair on a line). */
    public List<Trip> listTrips(String fromStation, String toStation) {
        if (fromStation != null && !fromStation.isBlank() && toStation != null && !toStation.isBlank()) {
            String from = fromStation.trim();
            String to = toStation.trim();
            List<Trip> exact = tripRepository.findByFromStationAndToStation(from, to);
            if (!exact.isEmpty()) return exact;
            // Segment lookup: find routes that serve from → to, then return trips for those route endpoints
            List<RouteConfig.EndpointPair> pairs = routeConfig.endpointPairsForSegment(from, to);
            Set<Trip> merged = new LinkedHashSet<>();
            for (RouteConfig.EndpointPair p : pairs) {
                merged.addAll(tripRepository.findByFromStationAndToStation(p.from(), p.to()));
            }
            return merged.stream().sorted(Comparator.comparing(Trip::getDepartureTime)).toList();
        }
        // No filter: default to Hallam Line (Leeds → Sheffield); fallback to all if none exist yet
        List<Trip> hallam = tripRepository.findByFromStationAndToStation("Leeds", "Sheffield");
        return hallam.isEmpty() ? tripRepository.findAllWithTrain() : hallam;
    }

    /** Departures from a given station (departureTime >= now), ordered by time. Used for dashboard. */
    public List<Trip> listDeparturesFromStation(String stationName) {
        return tripRepository.findByFromStationAndDepartureTimeAfterOrderByDepartureTimeAsc(stationName, Instant.now());
    }

    public List<TripSeat> getSeatsForTrip(Long tripId) {
        return tripSeatRepository.findByTripId(tripId);
    }

    /**
     * Returns seat IDs that are currently reserved/paid/confirmed (not expired).
     */
    public List<Long> getBookedSeatIdsForTrip(Long tripId) {
        List<TripSeat> seats = tripSeatRepository.findByTripId(tripId);
        Instant now = Instant.now();
        return seats.stream()
            .filter(ts -> reservationRepository.findActiveByTripSeatId(
                ts.getId(),
                List.of(ReservationStatus.RESERVED, ReservationStatus.PENDING_PAYMENT, ReservationStatus.PAYMENT_PROCESSING, ReservationStatus.PAID, ReservationStatus.CONFIRMED),
                now
            ).isPresent())
            .map(ts -> ts.getSeat().getId())
            .collect(Collectors.toList());
    }
}

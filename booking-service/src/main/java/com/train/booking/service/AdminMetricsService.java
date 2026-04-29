package com.train.booking.service;

import com.train.booking.api.dto.AdminMetricsDto;
import com.train.booking.domain.FareStatus;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.domain.StationEntryAction;
import com.train.booking.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregates counts for enterprise KPI dashboard (booking, fare evasion, system).
 */
@Service
@RequiredArgsConstructor
public class AdminMetricsService {

    private final ReservationRepository reservationRepository;
    private final TripRepository tripRepository;
    private final TrainRepository trainRepository;
    private final GeofenceEventRepository geofenceEventRepository;
    private final StationEntryActionRepository stationEntryActionRepository;
    private final TripSegmentRepository tripSegmentRepository;

    public AdminMetricsDto getMetrics() {
        Map<String, Long> reservationsByStatus = new HashMap<>();
        for (ReservationStatus s : ReservationStatus.values()) {
            reservationsByStatus.put(s.name(), reservationRepository.countByStatus(s));
        }
        long totalReservations = reservationsByStatus.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Long> stationEntryActionsByStatus = new HashMap<>();
        for (StationEntryAction.Status s : StationEntryAction.Status.values()) {
            stationEntryActionsByStatus.put(s.name(), stationEntryActionRepository.countByStatus(s));
        }
        long totalStationEntryActions = stationEntryActionsByStatus.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Long> tripSegmentsByFareStatus = new HashMap<>();
        for (FareStatus f : FareStatus.values()) {
            tripSegmentsByFareStatus.put(f.name(), tripSegmentRepository.countByFareStatus(f));
        }
        long totalTripSegments = tripSegmentsByFareStatus.values().stream().mapToLong(Long::longValue).sum();

        long confirmed = reservationsByStatus.getOrDefault(ReservationStatus.CONFIRMED.name(), 0L);
        long releasedSeats = reservationsByStatus.getOrDefault(ReservationStatus.EXPIRED.name(), 0L)
            + reservationsByStatus.getOrDefault(ReservationStatus.CANCELLED.name(), 0L);

        return AdminMetricsDto.builder()
            .totalReservations(totalReservations)
            .reservationsByStatus(reservationsByStatus)
            .totalTrips(tripRepository.count())
            .totalTrains(trainRepository.count())
            .totalGeofenceEvents(geofenceEventRepository.count())
            .totalStationEntryActions(totalStationEntryActions)
            .stationEntryActionsByStatus(stationEntryActionsByStatus)
            .totalTripSegments(totalTripSegments)
            .tripSegmentsByFareStatus(tripSegmentsByFareStatus)
            .confirmedBookings(confirmed)
            .releasedSeats(releasedSeats)
            .build();
    }
}

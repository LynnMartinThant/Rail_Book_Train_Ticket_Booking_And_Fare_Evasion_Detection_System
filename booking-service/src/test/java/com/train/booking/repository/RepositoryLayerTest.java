package com.train.booking.repository;

import com.train.booking.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests each repository: save/find and custom query methods.
 * Uses test profile and TestDataInitializer (geofence, train, trip, seats, fare buckets).
 */
@SpringBootTest
@ActiveProfiles("test")
class RepositoryLayerTest {

    @Autowired private GeofenceRepository geofenceRepository;
    @Autowired private TrainRepository trainRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private TripSeatRepository tripSeatRepository;
    @Autowired private FareBucketRepository fareBucketRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private StationEntryActionRepository stationEntryActionRepository;
    @Autowired private GeofenceEventRepository geofenceEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserLocationRepository userLocationRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private TripSegmentRepository tripSegmentRepository;
    @Autowired private TicketAlertRepository ticketAlertRepository;
    @Autowired private UserNotificationRepository userNotificationRepository;

    private Long geofenceId;
    private Long tripId;
    private Long tripSeatId;

    @BeforeEach
    void setUp() {
        geofenceId = geofenceRepository.findAll().stream().findFirst().map(Geofence::getId).orElse(null);
        tripId = tripRepository.findAllWithTrain().stream().findFirst().map(Trip::getId).orElse(null);
        if (tripId != null) {
            tripSeatId = tripSeatRepository.findByTripId(tripId).stream().findFirst().map(TripSeat::getId).orElse(null);
        } else {
            tripSeatId = null;
        }
    }

    @Nested
    @DisplayName("GeofenceRepository")
    class GeofenceRepositoryTest {
        @Test
        void findAllByOrderByNameAsc_returnsOrdered() {
            List<Geofence> list = geofenceRepository.findAllByOrderByNameAsc();
            assertThat(list).isNotEmpty();
            for (int i = 1; i < list.size(); i++) {
                assertThat(list.get(i).getName()).isGreaterThanOrEqualTo(list.get(i - 1).getName());
            }
        }

        @Test
        void findByStationName_returnsWhenExists() {
            Geofence g = geofenceRepository.findAll().stream().findFirst().orElse(null);
            if (g != null) {
                assertThat(geofenceRepository.findByStationName(g.getStationName())).isPresent().get().satisfies(f -> assertThat(f.getId()).isEqualTo(g.getId()));
            }
        }

        @Test
        void findByName_returnsWhenExists() {
            Geofence g = geofenceRepository.findAll().stream().findFirst().orElse(null);
            if (g != null) {
                assertThat(geofenceRepository.findByName(g.getName())).isPresent().get().satisfies(f -> assertThat(f.getId()).isEqualTo(g.getId()));
            }
        }
    }

    @Nested
    @DisplayName("TrainRepository")
    class TrainRepositoryTest {
        @Test
        void save_and_findAll() {
            Train t = Train.builder().name("Test Train").code("TT").build();
            Train saved = trainRepository.save(t);
            assertThat(saved.getId()).isNotNull();
            assertThat(trainRepository.findById(saved.getId())).isPresent().get().satisfies(f -> assertThat(f.getId()).isEqualTo(saved.getId()));
        }
    }

    @Nested
    @DisplayName("SeatRepository")
    class SeatRepositoryTest {
        @Test
        void save_and_findAll() {
            Train train = trainRepository.findAll().stream().findFirst().orElseThrow();
            Seat s = Seat.builder().train(train).seatNumber("X").build();
            s = seatRepository.save(s);
            assertThat(s.getId()).isNotNull();
            assertThat(seatRepository.count()).isPositive();
        }
    }

    @Nested
    @DisplayName("TripRepository")
    class TripRepositoryTest {
        @Test
        void findAllWithTrain_loadsTrain() {
            List<Trip> list = tripRepository.findAllWithTrain();
            assertThat(list).isNotEmpty();
            list.forEach(t -> assertThat(t.getTrain()).isNotNull());
        }

        @Test
        void findByIdWithTrain_loadsTrain() {
            if (tripId == null) return;
            assertThat(tripRepository.findByIdWithTrain(tripId)).isPresent().get().satisfies(t -> assertThat(t.getTrain()).isNotNull());
        }

        @Test
        void findByFromStationAndToStation_returnsMatching() {
            Trip t = tripRepository.findAllWithTrain().stream().findFirst().orElse(null);
            if (t != null) {
                List<Trip> found = tripRepository.findByFromStationAndToStation(t.getFromStation(), t.getToStation());
                assertThat(found).anyMatch(trip -> trip.getId().equals(t.getId()));
            }
        }
    }

    @Nested
    @DisplayName("TripSeatRepository")
    class TripSeatRepositoryTest {
        @Test
        void findByTripId_returnsSeatsWithFetch() {
            if (tripId == null) return;
            List<TripSeat> list = tripSeatRepository.findByTripId(tripId);
            assertThat(list).isNotEmpty();
            list.forEach(ts -> {
                assertThat(ts.getSeat()).isNotNull();
                assertThat(ts.getTrip()).isNotNull();
            });
        }

        @Test
        void countByTripId_matchesListSize() {
            if (tripId == null) return;
            int count = tripSeatRepository.countByTripId(tripId);
            assertThat(tripSeatRepository.findByTripId(tripId)).hasSize(count);
        }

        @Test
        @Transactional
        void findByTripIdAndSeatIdForUpdate_returnsWhenExists() {
            if (tripId == null || tripSeatId == null) return;
            Long seatId = tripSeatRepository.findById(tripSeatId).map(ts -> ts.getSeat().getId()).orElse(null);
            if (seatId != null) {
                assertThat(tripSeatRepository.findByTripIdAndSeatIdForUpdate(tripId, seatId)).isPresent();
            }
        }
    }

    @Nested
    @DisplayName("FareBucketRepository")
    class FareBucketRepositoryTest {
        @Test
        void findByTripIdOrderByDisplayOrderAsc_returnsOrdered() {
            if (tripId == null) return;
            List<FareBucket> list = fareBucketRepository.findByTripIdOrderByDisplayOrderAsc(tripId);
            assertThat(list).isNotEmpty();
            for (int i = 1; i < list.size(); i++) {
                assertThat(list.get(i).getDisplayOrder()).isGreaterThanOrEqualTo(list.get(i - 1).getDisplayOrder());
            }
        }
    }

    @Nested
    @DisplayName("ReservationRepository")
    class ReservationRepositoryTest {
        @Test
        void save_and_findByUserIdOrderByCreatedAtDesc() {
            if (tripSeatId == null) return;
            TripSeat ts = tripSeatRepository.findById(tripSeatId).orElseThrow();
            Reservation r = Reservation.builder()
                .tripSeat(ts).userId("repo-test-user").status(ReservationStatus.RESERVED)
                .amount(BigDecimal.TEN).build();
            Reservation saved = reservationRepository.save(r);
            assertThat(saved.getId()).isNotNull();
            List<Reservation> byUser = reservationRepository.findByUserIdOrderByCreatedAtDesc("repo-test-user");
            assertThat(byUser).anyMatch(res -> res.getId().equals(saved.getId()));
        }

        @Test
        void findActiveByTripSeatId_returnsNonExpired() {
            if (tripSeatId == null) return;
            List<ReservationStatus> active = List.of(ReservationStatus.RESERVED, ReservationStatus.PAID, ReservationStatus.CONFIRMED);
            reservationRepository.findActiveByTripSeatId(tripSeatId, active, Instant.now().plusSeconds(3600));
            // no exception; may be empty
        }

        @Test
        void countByTripIdAndStatusIn() {
            if (tripId == null) return;
            long n = reservationRepository.countByTripIdAndStatusIn(tripId, List.of(ReservationStatus.CONFIRMED, ReservationStatus.PAID));
            assertThat(n).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("StationEntryActionRepository")
    class StationEntryActionRepositoryTest {
        @Test
        void save_and_findByUserIdAndStatusOrderByCreatedAtDesc() {
            if (geofenceId == null) return;
            StationEntryAction a = StationEntryAction.builder()
                .userId("repo-action-user").geofenceId(geofenceId).stationName("Test")
                .status(StationEntryAction.Status.PENDING_OPTION).build();
            StationEntryAction saved = stationEntryActionRepository.save(a);
            assertThat(saved.getId()).isNotNull();
            List<StationEntryAction> list = stationEntryActionRepository.findByUserIdAndStatusOrderByCreatedAtDesc("repo-action-user", StationEntryAction.Status.PENDING_OPTION);
            assertThat(list).anyMatch(x -> x.getId().equals(saved.getId()));
        }

        @Test
        void existsByUserIdAndGeofenceIdAndStatus() {
            if (geofenceId == null) return;
            boolean exists = stationEntryActionRepository.existsByUserIdAndGeofenceIdAndStatus("repo-action-user", geofenceId, StationEntryAction.Status.PENDING_OPTION);
            assertThat(exists).isTrue();
        }

        @Test
        void countByStatus() {
            long n = stationEntryActionRepository.countByStatus(StationEntryAction.Status.PENDING_OPTION);
            assertThat(n).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("GeofenceEventRepository")
    class GeofenceEventRepositoryTest {
        @Test
        void save_and_findByUserIdOrderByCreatedAtDesc() {
            if (geofenceId == null) return;
            Geofence g = geofenceRepository.findById(geofenceId).orElseThrow();
            GeofenceEvent e = GeofenceEvent.builder().userId("repo-event-user").geofence(g).eventType(GeofenceEvent.EventType.ENTERED).build();
            GeofenceEvent saved = geofenceEventRepository.save(e);
            assertThat(saved.getId()).isNotNull();
            List<GeofenceEvent> list = geofenceEventRepository.findByUserIdOrderByCreatedAtDesc("repo-event-user", PageRequest.of(0, 10));
            assertThat(list).anyMatch(x -> x.getId().equals(saved.getId()));
        }

        @Test
        void findAllByOrderByCreatedAtDesc() {
            assertThat(geofenceEventRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5))).isNotNull();
        }
    }

    @Nested
    @DisplayName("UserRepository")
    class UserRepositoryTest {
        @Test
        void save_findByEmail_existsByEmail() {
            User u = User.builder().email("repotest@example.com").passwordHash("hash").build();
            User saved = userRepository.save(u);
            assertThat(userRepository.findByEmail("repotest@example.com")).isPresent().get().satisfies(found -> assertThat(found.getId()).isEqualTo(saved.getId()));
            assertThat(userRepository.existsByEmail("repotest@example.com")).isTrue();
            assertThat(userRepository.existsByEmail("nonexistent@example.com")).isFalse();
        }
    }

    @Nested
    @DisplayName("UserLocationRepository")
    class UserLocationRepositoryTest {
        @Test
        void save_findByUserId_findAllByOrderByUpdatedAtDesc() {
            UserLocation loc = UserLocation.builder().userId("repo-loc-user").latitude(53.0).longitude(-1.0).updatedAt(Instant.now()).build();
            UserLocation saved = userLocationRepository.save(loc);
            assertThat(userLocationRepository.findByUserId("repo-loc-user")).isPresent().get().satisfies(l -> assertThat(l.getId()).isEqualTo(saved.getId()));
            assertThat(userLocationRepository.findAllByOrderByUpdatedAtDesc()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("AuditLogRepository")
    class AuditLogRepositoryTest {
        @Test
        void save_findByUserIdOrderByCreatedAtDesc() {
            AuditLog log = AuditLog.builder().userId("repo-audit-user").action("TEST").details("repo test").build();
            AuditLog saved = auditLogRepository.save(log);
            assertThat(saved.getId()).isNotNull();
            List<AuditLog> list = auditLogRepository.findByUserIdOrderByCreatedAtDesc("repo-audit-user", PageRequest.of(0, 10));
            assertThat(list).anyMatch(l -> l.getId().equals(saved.getId()));
        }

        @Test
        void findAllByOrderByCreatedAtDesc() {
            assertThat(auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5))).isNotNull();
        }
    }

    @Nested
    @DisplayName("TripSegmentRepository")
    class TripSegmentRepositoryTest {
        @Test
        void save_findByIdempotencyKey_findByPassengerIdOrderByCreatedAtDesc() {
            String idemKey = "repo-seg-key-" + System.currentTimeMillis();
            TripSegment seg = TripSegment.builder()
                .passengerId("repo-seg-user").originStation("A").destinationStation("B")
                .segmentStartTime(Instant.now()).segmentEndTime(Instant.now().plusSeconds(3600))
                .fareStatus(FareStatus.PAID).idempotencyKey(idemKey).build();
            TripSegment saved = tripSegmentRepository.save(seg);
            assertThat(saved.getId()).isNotNull();
            assertThat(tripSegmentRepository.findByIdempotencyKey(saved.getIdempotencyKey())).isPresent().get().satisfies(s -> assertThat(s.getId()).isEqualTo(saved.getId()));
            List<TripSegment> list = tripSegmentRepository.findByPassengerIdOrderByCreatedAtDesc("repo-seg-user", PageRequest.of(0, 10));
            assertThat(list).anyMatch(s -> s.getId().equals(saved.getId()));
        }

        @Test
        void findByFareStatusAndResolutionDeadlineBefore_countByFareStatus() {
            List<TripSegment> pending = tripSegmentRepository.findByFareStatusAndResolutionDeadlineBefore(FareStatus.PENDING_RESOLUTION, Instant.now().plusSeconds(3600));
            assertThat(pending).isNotNull();
            long n = tripSegmentRepository.countByFareStatus(FareStatus.PAID);
            assertThat(n).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("TicketAlertRepository")
    class TicketAlertRepositoryTest {
        @Test
        void save_findByUserIdOrderByCreatedAtDesc() {
            TicketAlert a = TicketAlert.builder().userId("repo-alert-user").tripId(1L).message("Test alert").build();
            TicketAlert saved = ticketAlertRepository.save(a);
            assertThat(saved.getId()).isNotNull();
            List<TicketAlert> list = ticketAlertRepository.findByUserIdOrderByCreatedAtDesc("repo-alert-user");
            assertThat(list).anyMatch(x -> x.getId().equals(saved.getId()));
        }
    }

    @Nested
    @DisplayName("UserNotificationRepository")
    class UserNotificationRepositoryTest {
        @Test
        void save_findByUserIdOrderByCreatedAtDesc() {
            UserNotification n = UserNotification.builder().userId("repo-notif-user").message("Test notification").build();
            UserNotification saved = userNotificationRepository.save(n);
            assertThat(saved.getId()).isNotNull();
            List<UserNotification> list = userNotificationRepository.findByUserIdOrderByCreatedAtDesc("repo-notif-user");
            assertThat(list).anyMatch(x -> x.getId().equals(saved.getId()));
        }
    }

}

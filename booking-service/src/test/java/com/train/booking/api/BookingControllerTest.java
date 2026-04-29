package com.train.booking.api;

import com.train.booking.domain.*;
import com.train.booking.movement.projection.PassengerMovementViewRepository;
import com.train.booking.service.*;
import com.train.booking.repository.AuditDecisionRecordRepository;
import com.train.booking.repository.ReservationRepository;
import com.train.booking.repository.SegmentTransitionRepository;
import com.train.booking.movement.eventlog.MovementEventRepository;
import com.train.booking.repository.TicketAlertRepository;
import com.train.booking.repository.TripRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer tests for BookingController (API endpoints).
 */
@WebMvcTest(BookingController.class)
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;
    @MockBean
    private TripQueryService tripQueryService;
    @MockBean
    private ReservationRepository reservationRepository;
    @MockBean
    private TicketAlertRepository ticketAlertRepository;
    @MockBean
    private TripRepository tripRepository;
    @MockBean
    private AuditLogService auditLogService;
    @MockBean
    private GeofenceService geofenceService;
    @MockBean
    private LocationService locationService;
    @MockBean
    private com.train.booking.repository.UserNotificationRepository userNotificationRepository;
    @MockBean
    private GeofenceRulesService geofenceRulesService;
    @MockBean
    private TripSegmentService tripSegmentService;
    @MockBean
    private LoadTestService loadTestService;
    @MockBean
    private SimulationService simulationService;
    @MockBean
    private DemoScenarioService demoScenarioService;
    @MockBean
    private DisputeService disputeService;
    @MockBean
    private FraudDetectionService fraudDetectionService;
    @MockBean
    private TicketPdfService ticketPdfService;
    @MockBean
    private PricingService pricingService;
    @MockBean
    private PassengerMovementViewRepository passengerMovementViewRepository;
    @MockBean
    private MovementEventRepository movementEventRepository;
    @MockBean
    private AuditDecisionRecordRepository auditDecisionRecordRepository;
    @MockBean
    private SegmentTransitionRepository segmentTransitionRepository;

    @Test
    void listTrips_returnsListFromTripQueryService() throws Exception { //api/trip
        Train train = Train.builder().id(1L).name("Northern").code("NT").build();
        Trip t = Trip.builder().id(1L).fromStation("Leeds").toStation("Sheffield").departureTime(Instant.now().plusSeconds(3600)).pricePerSeat(BigDecimal.TEN).train(train).build();
        when(tripQueryService.listTrips(null, null)).thenReturn(List.of(t));
        when(pricingService.getPrice(any(), any())).thenReturn(new PricingService.PricingResult(BigDecimal.TEN, "STANDARD", false));
        mockMvc.perform(get("/api/trips"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void reserve_returnsReservationsFromBookingService() throws Exception { // api/reserve
        Train train = Train.builder().id(1L).name("Northern").code("NT").build();
        Seat seat = Seat.builder().id(1L).seatNumber("1").train(train).build();
        Trip trip = Trip.builder().id(1L).fromStation("Leeds").toStation("Sheffield").departureTime(Instant.now().plusSeconds(3600)).pricePerSeat(BigDecimal.TEN).train(train).build();
        TripSeat tripSeat = TripSeat.builder().id(1L).trip(trip).seat(seat).build();
        Reservation r = Reservation.builder().id(1L).tripSeat(tripSeat).userId("user1").status(ReservationStatus.RESERVED).amount(BigDecimal.TEN).build();
        when(bookingService.reserve(eq("user1"), eq(1L), anyList(), isNull(), isNull())).thenReturn(List.of(r));
        mockMvc.perform(post("/api/reserve")
                .header("X-User-Id", "user1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tripId\":1,\"seatIds\":[1]}"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void reserve_withoutUserHeader_returns401or400() throws Exception { 
        mockMvc.perform(post("/api/reserve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tripId\":1,\"seatIds\":[1]}"))
            .andExpect(status().is4xxClientError());
    }
}

package com.train.booking.service;

import com.train.booking.domain.UserLocation;
import com.train.booking.movement.eventlog.MovementEventWriter;
import com.train.booking.movement.metrics.MovementPipelineMetrics;
import com.train.booking.quality.DataQualityAssessment;
import com.train.booking.quality.DataQualityScoringService;
import com.train.booking.repository.UserLocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LocationService (ingestion + delegation to station layer for geofence transitions).
 */
@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    @Mock
    private UserLocationRepository userLocationRepository;
    @Mock
    private GeofenceService geofenceService;
    @Mock
    private MovementEventWriter movementEventWriter;
    @Mock
    private MovementPipelineMetrics movementPipelineMetrics;
    @Mock
    private DataQualityScoringService dataQualityScoringService;

    private LocationService locationService;

    @BeforeEach
    void setUp() {
        lenient().when(dataQualityScoringService.assess(any(), any(), anyDouble(), anyBoolean()))
            .thenReturn(new DataQualityAssessment(true, true, 1.0, List.of()));
        locationService = new LocationService(
            userLocationRepository,
            geofenceService,
            movementEventWriter,
            movementPipelineMetrics,
            dataQualityScoringService
        );
    }

    @Test
    void reportLocation_savesUserLocation() {
        when(userLocationRepository.findByUserId("user1")).thenReturn(java.util.Optional.empty());
        UserLocation saved = UserLocation.builder().id(1L).userId("user1").latitude(53.0).longitude(-1.0).updatedAt(java.time.Instant.now()).build();
        when(userLocationRepository.save(any(UserLocation.class))).thenReturn(saved);

        UserLocation result = locationService.reportLocation("user1", 53.0, -1.0);

        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo("user1");
        verify(userLocationRepository).save(any(UserLocation.class));
        verify(geofenceService).applyLocationReportForStationTransitions(eq("user1"), isNull(), eq(53.0), eq(-1.0), isNull(), anyString());
    }

    @Test
    void reportLocation_whenPreviousExists_delegatesTransitionDetectionToStationLayer() {
        UserLocation previous = UserLocation.builder().id(1L).userId("user1").latitude(53.0).longitude(-1.0).updatedAt(java.time.Instant.now()).build();
        when(userLocationRepository.findByUserId("user1")).thenReturn(java.util.Optional.of(previous));
        UserLocation saved = UserLocation.builder().id(1L).userId("user1").latitude(53.38).longitude(-1.47).updatedAt(java.time.Instant.now()).build();
        when(userLocationRepository.save(any(UserLocation.class))).thenReturn(saved);

        locationService.reportLocation("user1", 53.38, -1.47);

        ArgumentCaptor<UserLocation> prevCap = ArgumentCaptor.forClass(UserLocation.class);
        verify(geofenceService).applyLocationReportForStationTransitions(eq("user1"), prevCap.capture(), eq(53.38), eq(-1.47), isNull(), anyString());
        assertThat(prevCap.getValue()).isSameAs(previous);
    }

    @Test
    void getAllUserLocations_returnsFromRepository() {
        List<UserLocation> list = List.of(
            UserLocation.builder().id(1L).userId("u1").latitude(53.0).longitude(-1.0).updatedAt(java.time.Instant.now()).build());
        when(userLocationRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(list);
        assertThat(locationService.getAllUserLocations()).isEqualTo(list);
    }
}

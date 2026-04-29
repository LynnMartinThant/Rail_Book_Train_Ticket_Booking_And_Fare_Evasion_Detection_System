package com.train.booking.event;

import com.train.booking.service.GeofenceRulesService;
import com.train.booking.service.JourneyDroolsPipelineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocationEventPipelineConsumerTest {

    @Mock
    private GeofenceRulesService geofenceRulesService;
    @Mock
    private JourneyDroolsPipelineService journeyDroolsPipelineService;
    @InjectMocks
    private LocationEventPipelineConsumer consumer;

    @Test
    void whenEventDrivenEnabled_legacyJourneyPipelineIsSkippedButEntryValidationRuns() {
        ReflectionTestUtils.setField(consumer, "eventDrivenPipelineEnabled", true);

        consumer.handle("u1", 1L, "ENTERED", "Sheffield", Instant.now(), 5.0, "corr-1");

        verify(geofenceRulesService).onStationEntry("u1", "Sheffield", 1L, "corr-1");
        verifyNoInteractions(journeyDroolsPipelineService);
    }

    @Test
    void whenEventDrivenDisabled_legacyJourneyPipelineRuns() {
        ReflectionTestUtils.setField(consumer, "eventDrivenPipelineEnabled", false);
        when(journeyDroolsPipelineService.runPipelineForUser("u1"))
            .thenReturn(new JourneyDroolsPipelineService.PipelineResult());

        consumer.handle("u1", 1L, "ENTERED", "Sheffield", Instant.now(), 5.0, "corr-2");

        verify(geofenceRulesService).onStationEntry("u1", "Sheffield", 1L, "corr-2");
        verify(journeyDroolsPipelineService).runPipelineForUser("u1");
    }
}


package com.train.booking.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StationEntryActionDto {
    private Long id;
    private Long geofenceId;
    private String stationName;
    private String status;
    private String responseType;
    private Instant createdAt;
    private Instant respondedAt;
    private Long qrValidatedReservationId;
}

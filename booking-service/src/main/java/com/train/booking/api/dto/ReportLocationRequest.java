package com.train.booking.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;


@Data
public class ReportLocationRequest { // report location request for user location
    @NotNull(message = "latitude is required")
    private Double latitude;
    @NotNull(message = "longitude is required")
    private Double longitude;

    private Double accuracyMeters;
}

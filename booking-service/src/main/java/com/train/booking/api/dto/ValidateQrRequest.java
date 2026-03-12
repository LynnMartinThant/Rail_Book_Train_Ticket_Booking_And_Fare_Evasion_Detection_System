package com.train.booking.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ValidateQrRequest {
    /** Reservation ID from scanned ticket QR (payload can be reservationId or encoded token). */
    @NotNull(message = "reservationId is required")
    private Long reservationId;
}

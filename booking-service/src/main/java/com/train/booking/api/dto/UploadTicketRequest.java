package com.train.booking.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UploadTicketRequest {
    /** Reservation ID from ticket (typed or decoded from QR). */
    @NotNull(message = "reservationId is required")
    private Long reservationId;
}

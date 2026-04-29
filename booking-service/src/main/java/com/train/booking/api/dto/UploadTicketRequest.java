package com.train.booking.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UploadTicketRequest {
    /** Reservation ID from ticket (typed or decoded from QR). */
    @NotNull(message = "reservationId is required")
    private Long reservationId;
    /** Passenger reason for dispute (optional). */
    private String reason;
    /** Evidence URI/reference (optional: file id, blob key, url). */
    private String evidenceReference;
}

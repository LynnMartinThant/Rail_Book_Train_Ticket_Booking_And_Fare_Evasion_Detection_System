package com.train.booking.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SeatDto {
    private Long seatId;
    private String seatNumber;
    private boolean available;
}

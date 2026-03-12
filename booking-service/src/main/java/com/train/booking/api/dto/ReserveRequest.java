package com.train.booking.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ReserveRequest {
    @NotNull
    private Long tripId;
    @NotEmpty
    private List<Long> seatIds;
}

package com.train.booking.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreatePaymentIntentRequest {
    @NotBlank(message = "gateway is required (e.g. STRIPE)")
    @Pattern(regexp = "STRIPE", message = "Supported: STRIPE (Visa, Apple Pay)")
    private String gateway;
}

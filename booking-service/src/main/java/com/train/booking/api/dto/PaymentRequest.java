package com.train.booking.api.dto;

import lombok.Data;

@Data
public class PaymentRequest {
    /** Optional; if empty, server auto-generates ID in format dd/mm/yy-NNNN */
    private String paymentReference;
}

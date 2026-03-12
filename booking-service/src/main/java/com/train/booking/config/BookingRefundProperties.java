package com.train.booking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "booking.refund")
public class BookingRefundProperties {

    /** User can request a refund within this many minutes after payment. */
    private int requestWindowMinutes = 3;
}

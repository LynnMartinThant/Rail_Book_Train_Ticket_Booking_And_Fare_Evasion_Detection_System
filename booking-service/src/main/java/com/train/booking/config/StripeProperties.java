package com.train.booking.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "stripe")
public class StripeProperties {
    /** Secret key (sk_...) for creating PaymentIntents. */
    private String apiKey = "";
    /** Webhook signing secret (whsec_...) for verifying webhook payloads. */
    private String webhookSecret = "";
}

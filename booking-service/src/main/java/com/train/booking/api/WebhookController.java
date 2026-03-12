package com.train.booking.api;

import com.train.booking.service.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Payment gateway webhooks. Must receive raw body for signature verification (e.g. Stripe).
 */
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private static final String STRIPE_SIGNATURE_HEADER = "Stripe-Signature";

    private final PaymentGatewayService paymentGatewayService;

    @PostMapping("/stripe")
    public ResponseEntity<String> stripeWebhook(
        @RequestBody String payload,
        @RequestHeader(value = STRIPE_SIGNATURE_HEADER, required = false) String stripeSignature
    ) {
        if (payload == null || payload.isBlank()) {
            return ResponseEntity.badRequest().body("Missing body");
        }
        if (stripeSignature == null || stripeSignature.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing Stripe-Signature");
        }
        boolean handled = paymentGatewayService.handleStripeWebhook(payload, stripeSignature);
        return handled ? ResponseEntity.ok("OK") : ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Verification failed");
    }
}

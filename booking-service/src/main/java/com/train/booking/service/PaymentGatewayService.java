package com.train.booking.service;

import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import com.train.booking.repository.ReservationRepository;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Payment gateway integration (Stripe: Visa, Apple Pay).
 * Never trust the client: payment is verified server-side via webhook.
 */
@Service
@Slf4j
public class PaymentGatewayService {

    private static final String GATEWAY_STRIPE = "STRIPE";
    private static final String CURRENCY_GBP = "gbp";

    @Value("${stripe.api-key:}")
    private String stripeApiKey;

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    @Value("${stripe.demo-mode:false}")
    private boolean stripeDemoMode;

    private final ReservationRepository reservationRepository;
    private final BookingPolicyService policyService;
    public PaymentGatewayService(ReservationRepository reservationRepository, BookingPolicyService policyService) {
        this.reservationRepository = reservationRepository;
        this.policyService = policyService;
    }

    @PostConstruct
    void init() {
        if (stripeApiKey != null && !stripeApiKey.isBlank()) {
            Stripe.apiKey = stripeApiKey;
        }
    }

    public List<String> getAvailablePaymentMethods() {
        if (stripeApiKey != null && !stripeApiKey.isBlank()) {
            return List.of("card", "apple_pay");
        }
        if (stripeDemoMode) {
            return List.of("card", "apple_pay"); // demo mode: show options without real Stripe
        }
        return List.of();
    }

    /** Demo mode: simulate gateway success without Stripe (for local testing). */
    private CreatePaymentIntentResult createPaymentIntentDemo(Reservation r) {
        String demoId = "demo_" + r.getId() + "_" + System.currentTimeMillis();
        r.setStatus(ReservationStatus.PAYMENT_PROCESSING);
        r.setPaymentGateway(GATEWAY_STRIPE);
        r.setPaymentTransactionId(demoId);
        r.setCurrency(CURRENCY_GBP.toUpperCase());
        reservationRepository.save(r);
        r.setStatus(ReservationStatus.PAID);
        r.setPaymentReference(demoId);
        reservationRepository.save(r);
        r.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(r);
        log.info("Demo payment: reservation {} marked PAID and CONFIRMED", r.getId());
        return CreatePaymentIntentResult.builder()
            .clientSecret("demo_ok")
            .paymentIntentId(demoId)
            .amount(r.getAmount())
            .currency(CURRENCY_GBP.toUpperCase())
            .build();
    }

    /**
     * Create a temporary booking (PENDING_PAYMENT) or use existing RESERVED, then create Stripe PaymentIntent and set PAYMENT_PROCESSING.
     * Returns clientSecret for frontend to complete payment (Stripe Elements or Checkout).
     */
    @Transactional
    public CreatePaymentIntentResult createPaymentIntent(Long reservationId, String userId, String gateway) {
        if (!GATEWAY_STRIPE.equalsIgnoreCase(gateway)) {
            throw new IllegalArgumentException("Unsupported gateway: " + gateway + ". Use STRIPE.");
        }

        Reservation r = reservationRepository.findByIdAndUserIdWithDetails(reservationId, userId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation not found"));
        policyService.assertCanPay(r);

        boolean useRealStripe = stripeApiKey != null && !stripeApiKey.isBlank();
        if (!useRealStripe) {
            if (stripeDemoMode) {
                return createPaymentIntentDemo(r);
            }
            throw new IllegalStateException(
                "Stripe is not configured. Set stripe.api-key in application.yml or STRIPE_API_KEY env (get a test key from https://dashboard.stripe.com/test/apikeys), or set stripe.demo-mode: true for local testing.");
        }

        // Amount in smallest currency unit (pence for GBP)
        long amountPence = r.getAmount().multiply(BigDecimal.valueOf(100)).longValue();
        if (amountPence <= 0) {
            throw new IllegalStateException("Invalid amount for payment");
        }

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
            .setAmount(amountPence)
            .setCurrency(CURRENCY_GBP)
            .putMetadata("reservation_id", String.valueOf(r.getId()))
            .putMetadata("user_id", r.getUserId())
            .setAutomaticPaymentMethods(
                PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                    .setEnabled(true) // card, apple_pay, etc.
                    .build()
            )
            .build();

        PaymentIntent intent = null;
        try {
            intent = PaymentIntent.create(params);
        } catch (Exception e) {
            log.warn("Stripe PaymentIntent create failed: {}", e.getMessage());
            throw new IllegalStateException("Payment gateway error: " + e.getMessage());
        }

        r.setStatus(ReservationStatus.PAYMENT_PROCESSING);
        r.setPaymentGateway(GATEWAY_STRIPE);
        r.setPaymentTransactionId(intent.getId());
        r.setCurrency(CURRENCY_GBP.toUpperCase());
        reservationRepository.save(r);

        return CreatePaymentIntentResult.builder()
            .clientSecret(intent.getClientSecret())
            .paymentIntentId(intent.getId())
            .amount(r.getAmount())
            .currency(CURRENCY_GBP.toUpperCase())
            .build();
    }

    /**
     * Handle Stripe webhook. Verify signature, then on payment_intent.succeeded verify amount/currency and set PAID then CONFIRMED.
     * Idempotent: if reservation already PAID/CONFIRMED, no-op.
     */
    @Transactional
    public boolean handleStripeWebhook(String payload, String stripeSignatureHeader) {
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank()) {
            log.warn("Stripe webhook secret not set; rejecting webhook");
            return false;
        }
        Event event;
        try {
            event = Webhook.constructEvent(payload, stripeSignatureHeader, stripeWebhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Stripe webhook parse error: {}", e.getMessage());
            return false;
        }

        if ("payment_intent.succeeded".equals(event.getType())) {
            return handlePaymentIntentSucceeded(event);
        }
        if ("payment_intent.payment_failed".equals(event.getType())) {
            return handlePaymentIntentFailed(event);
        }
        log.debug("Unhandled Stripe event type: {}", event.getType());
        return true;
    }

    private boolean handlePaymentIntentSucceeded(Event event) {
        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElse(null);
        if (intent == null) return false;

        String paymentIntentId = intent.getId();
        Optional<Reservation> opt = reservationRepository.findByPaymentTransactionId(paymentIntentId);
        if (opt.isEmpty()) {
            log.warn("Stripe webhook: no reservation for payment_intent {}", paymentIntentId);
            return true; // acknowledge to avoid retries
        }
        Reservation r = opt.get();

        if (r.getStatus() == ReservationStatus.PAID || r.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Stripe webhook: reservation {} already PAID/CONFIRMED (idempotent)", r.getId());
            return true;
        }

        // Verify amount and currency (prevent fraud)
        long expectedPence = r.getAmount().multiply(BigDecimal.valueOf(100)).longValue();
        long receivedPence = intent.getAmountReceived();
        if (receivedPence != expectedPence) {
            log.warn("Stripe webhook: amount mismatch reservation {} expected {} pence got {}", r.getId(), expectedPence, receivedPence);
            r.setStatus(ReservationStatus.CANCELLED);
            reservationRepository.save(r);
            return true;
        }
        if (!CURRENCY_GBP.equalsIgnoreCase(intent.getCurrency())) {
            log.warn("Stripe webhook: currency mismatch reservation {} expected GBP got {}", r.getId(), intent.getCurrency());
            r.setStatus(ReservationStatus.CANCELLED);
            reservationRepository.save(r);
            return true;
        }

        r.setStatus(ReservationStatus.PAID);
        r.setPaymentReference(paymentIntentId);
        reservationRepository.save(r);
        r.setStatus(ReservationStatus.CONFIRMED);
        reservationRepository.save(r);
        log.info("Stripe webhook: reservation {} marked PAID and CONFIRMED", r.getId());
        return true;
    }

    private boolean handlePaymentIntentFailed(Event event) {
        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer().getObject().orElse(null);
        if (intent == null) return false;
        reservationRepository.findByPaymentTransactionId(intent.getId()).ifPresent(r -> {
            if (r.getStatus() == ReservationStatus.PAYMENT_PROCESSING) {
                r.setStatus(ReservationStatus.CANCELLED);
                reservationRepository.save(r);
                log.info("Stripe webhook: reservation {} CANCELLED (payment failed)", r.getId());
            }
        });
        return true;
    }

    @lombok.Data
    @lombok.Builder
    public static class CreatePaymentIntentResult {
        private String clientSecret;
        private String paymentIntentId;
        private BigDecimal amount;
        private String currency;
    }
}

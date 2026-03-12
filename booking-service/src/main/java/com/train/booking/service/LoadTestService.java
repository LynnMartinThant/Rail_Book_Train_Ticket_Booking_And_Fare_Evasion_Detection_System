package com.train.booking.service;

import com.train.booking.domain.GeofenceEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates real-world load: hundreds of concurrent geofence entries and ticket verifications.
 * Uses atomic DB transactions (handled in GeofenceService / GeofenceRulesService).
 */
@Service
@Slf4j
public class LoadTestService {

    private final GeofenceService geofenceService;
    private final GeofenceRulesService geofenceRulesService;

    public LoadTestService(GeofenceService geofenceService, GeofenceRulesService geofenceRulesService) {
        this.geofenceService = geofenceService;
        this.geofenceRulesService = geofenceRulesService;
    }

    /**
     * Simulate many users entering the same geofence simultaneously.
     * Each user ID is distinct (load-user-1 .. load-user-N). Idempotency prevents duplicate StationEntryActions per user+geofence.
     *
     * @param geofenceId   geofence to enter (must exist)
     * @param concurrentUsers number of distinct users (1..2000)
     * @return success count, failure count, and sample errors
     */
    public LoadTestResult runConcurrentGeofenceEntries(Long geofenceId, int concurrentUsers) {
        int n = Math.min(Math.max(1, concurrentUsers), 2000);
        geofenceService.getGeofence(geofenceId).orElseThrow(() -> new IllegalArgumentException("Geofence not found: " + geofenceId));

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failure = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();
        int maxErrors = 10;

        int threads = Math.min(n, 200);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                String userId = "load-user-" + i;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        GeofenceEvent event = geofenceService.recordEvent(userId, geofenceId, GeofenceEvent.EventType.ENTERED);
                        if (event != null) success.incrementAndGet();
                        else failure.incrementAndGet();
                    } catch (Exception e) {
                        failure.incrementAndGet();
                        synchronized (errors) {
                            if (errors.size() < maxErrors) errors.add(e.getMessage());
                        }
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            executor.shutdown();
        }

        return LoadTestResult.builder()
            .successCount(success.get())
            .failureCount(failure.get())
            .totalRequested(n)
            .errors(errors)
            .build();
    }

    /**
     * Simulate many ticket QR validations at the same time.
     * Each item is (actionId, userId, reservationId). Atomic transactions and unique qr_validated_reservation_id prevent double usage.
     *
     * @param validations list of (actionId, userId, reservationId); size capped at 500
     * @return success count, failure count, and sample errors
     */
    public LoadTestResult runConcurrentQrValidations(List<QrValidationRequest> validations) {
        if (validations == null || validations.isEmpty()) {
            return LoadTestResult.builder().successCount(0).failureCount(0).totalRequested(0).errors(List.of()).build();
        }
        int n = Math.min(validations.size(), 500);

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failure = new AtomicInteger(0);
        List<String> errors = new ArrayList<>();
        int maxErrors = 10;

        int threads = Math.min(n, 100);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                QrValidationRequest req = validations.get(i);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (geofenceRulesService.validateQrAndCompleteAction(req.getActionId(), req.getUserId(), req.getReservationId()).isPresent()) {
                            success.incrementAndGet();
                        } else {
                            failure.incrementAndGet();
                            synchronized (errors) {
                                if (errors.size() < maxErrors) errors.add("Validation returned empty for actionId=" + req.getActionId());
                            }
                        }
                    } catch (Exception e) {
                        failure.incrementAndGet();
                        synchronized (errors) {
                            if (errors.size() < maxErrors) errors.add(e.getMessage());
                        }
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } finally {
            executor.shutdown();
        }

        return LoadTestResult.builder()
            .successCount(success.get())
            .failureCount(failure.get())
            .totalRequested(n)
            .errors(errors)
            .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LoadTestResult {
        private int successCount;
        private int failureCount;
        private int totalRequested;
        private List<String> errors;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QrValidationRequest {
        private Long actionId;
        private String userId;
        private Long reservationId;
    }
}

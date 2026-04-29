package com.train.booking.memory;

import com.train.booking.domain.Reservation;
import com.train.booking.domain.ReservationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 2 — in-memory {@link Reservation}. Callers attach {@code tripSeat} like a detached entity; no join-fetch graph.
 */
@Component
public class InMemoryReservationStore {

    private final ConcurrentHashMap<Long, Reservation> byId = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public Reservation save(Reservation entity) {
        Instant now = Instant.now();
        if (entity.getId() == null) {
            entity.setId(seq.getAndIncrement());
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(now);
            }
        }
        entity.setUpdatedAt(now);
        byId.put(entity.getId(), entity);
        return entity;
    }

    public Optional<Reservation> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Reservation> findAll() {
        return new ArrayList<>(byId.values());
    }

    public List<Reservation> findByUserIdOrderByCreatedAtDesc(String userId) {
        if (userId == null) {
            return List.of();
        }
        return byId.values().stream()
                .filter(r -> userId.equals(r.getUserId()))
                .sorted(Comparator.comparing(Reservation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    public Optional<Reservation> findByIdAndUserId(Long id, String userId) {
        return findById(id).filter(r -> userId != null && userId.equals(r.getUserId()));
    }

    public List<Reservation> findByUserIdAndStatusInOrderByCreatedAtDesc(String userId, List<ReservationStatus> statuses) {
        if (userId == null || statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        return byId.values().stream()
                .filter(r -> userId.equals(r.getUserId()) && statuses.contains(r.getStatus()))
                .sorted(Comparator.comparing(Reservation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    public Optional<Reservation> findByPaymentTransactionId(String paymentTransactionId) {
        if (paymentTransactionId == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(r -> paymentTransactionId.equals(r.getPaymentTransactionId()))
                .findFirst();
    }

    public boolean existsById(Long id) {
        return byId.containsKey(id);
    }

    public void deleteById(Long id) {
        byId.remove(id);
    }

    public void delete(Reservation entity) {
        if (entity != null && entity.getId() != null) {
            deleteById(entity.getId());
        }
    }

    public void deleteByUserIdIn(List<String> userIds) {
        if (userIds == null) {
            return;
        }
        for (Reservation r : new ArrayList<>(byId.values())) {
            if (r.getUserId() != null && userIds.contains(r.getUserId())) {
                deleteById(r.getId());
            }
        }
    }

    public void deleteAll() {
        byId.clear();
    }

    public long count() {
        return byId.size();
    }
}

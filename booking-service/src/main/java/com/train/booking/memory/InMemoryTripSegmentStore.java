package com.train.booking.memory;

import com.train.booking.domain.FareStatus;
import com.train.booking.domain.TripSegment;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 2 — in-memory {@link TripSegment}. Idempotency key maps to a single row (upsert on save).
 */
@Component
public class InMemoryTripSegmentStore {

    private final ConcurrentHashMap<Long, TripSegment> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> idempotencyToId = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public TripSegment save(TripSegment entity) {
        if (entity.getIdempotencyKey() != null) {
            Long existingId = idempotencyToId.get(entity.getIdempotencyKey());
            if (existingId != null) {
                entity.setId(existingId);
            }
        }
        if (entity.getId() == null) {
            long id = seq.getAndIncrement();
            entity.setId(id);
            if (entity.getIdempotencyKey() != null) {
                idempotencyToId.put(entity.getIdempotencyKey(), id);
            }
        } else if (entity.getIdempotencyKey() != null) {
            idempotencyToId.put(entity.getIdempotencyKey(), entity.getId());
        }
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(Instant.now());
        }
        byId.put(entity.getId(), entity);
        return entity;
    }

    public Optional<TripSegment> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<TripSegment> findAll() {
        return new ArrayList<>(byId.values());
    }

    public List<TripSegment> findByReservationId(Long reservationId) {
        if (reservationId == null) {
            return List.of();
        }
        return byId.values().stream()
                .filter(s -> reservationId.equals(s.getReservationId()))
                .toList();
    }

    public Optional<TripSegment> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        Long id = idempotencyToId.get(idempotencyKey);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public List<TripSegment> findByPassengerIdOrderByCreatedAtDesc(String passengerId, Pageable pageable) {
        if (passengerId == null) {
            return List.of();
        }
        List<TripSegment> sorted = byId.values().stream()
                .filter(s -> passengerId.equals(s.getPassengerId()))
                .sorted(Comparator.comparing(TripSegment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        return pageSlice(sorted, pageable);
    }

    public List<TripSegment> findByPassengerIdIn(List<String> passengerIds) {
        if (passengerIds == null || passengerIds.isEmpty()) {
            return List.of();
        }
        return byId.values().stream()
                .filter(s -> s.getPassengerId() != null && passengerIds.contains(s.getPassengerId()))
                .toList();
    }

    public List<TripSegment> findAllByOrderByCreatedAtDesc(Pageable pageable) {
        List<TripSegment> sorted = byId.values().stream()
                .sorted(Comparator.comparing(TripSegment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        return pageSlice(sorted, pageable);
    }

    public List<TripSegment> findAllWithReservationId() {
        return byId.values().stream()
                .filter(s -> s.getReservationId() != null)
                .toList();
    }

    public List<TripSegment> findByFareStatusAndResolutionDeadlineBefore(FareStatus fareStatus, Instant before) {
        if (fareStatus == null || before == null) {
            return List.of();
        }
        return byId.values().stream()
                .filter(s -> fareStatus.equals(s.getFareStatus())
                        && s.getResolutionDeadline() != null
                        && s.getResolutionDeadline().isBefore(before))
                .toList();
    }

    public List<TripSegment> findByFareStatusInOrderByCreatedAtDesc(List<FareStatus> statuses, Pageable pageable) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<TripSegment> sorted = byId.values().stream()
                .filter(s -> s.getFareStatus() != null && statuses.contains(s.getFareStatus()))
                .sorted(Comparator.comparing(TripSegment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        return pageSlice(sorted, pageable);
    }

    public long countByFareStatus(FareStatus fareStatus) {
        if (fareStatus == null) {
            return 0;
        }
        return byId.values().stream().filter(s -> fareStatus.equals(s.getFareStatus())).count();
    }

    public boolean existsById(Long id) {
        return byId.containsKey(id);
    }

    public void deleteById(Long id) {
        TripSegment removed = byId.remove(id);
        if (removed != null && removed.getIdempotencyKey() != null) {
            idempotencyToId.remove(removed.getIdempotencyKey(), id);
        }
    }

    public void delete(TripSegment entity) {
        if (entity != null && entity.getId() != null) {
            deleteById(entity.getId());
        }
    }

    public void deleteByPassengerIdIn(List<String> passengerIds) {
        if (passengerIds == null) {
            return;
        }
        for (TripSegment s : new ArrayList<>(byId.values())) {
            if (s.getPassengerId() != null && passengerIds.contains(s.getPassengerId())) {
                deleteById(s.getId());
            }
        }
    }

    public void deleteAll() {
        byId.clear();
        idempotencyToId.clear();
    }

    public long count() {
        return byId.size();
    }

    private static List<TripSegment> pageSlice(List<TripSegment> sorted, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return sorted;
        }
        int start = (int) Math.min(pageable.getOffset(), Integer.MAX_VALUE);
        int end = Math.min(start + pageable.getPageSize(), sorted.size());
        if (start >= sorted.size()) {
            return List.of();
        }
        return sorted.subList(start, end);
    }
}

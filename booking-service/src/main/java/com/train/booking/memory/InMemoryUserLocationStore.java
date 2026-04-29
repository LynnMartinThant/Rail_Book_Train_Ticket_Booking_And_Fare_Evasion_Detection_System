package com.train.booking.memory;

import com.train.booking.domain.UserLocation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 2 — in-memory projection of latest {@link UserLocation} per user (matches DB unique {@code user_id}).
 * Services in later steps inject this when running without JPA ({@code @Profile} or test slice).
 */
@Component
public class InMemoryUserLocationStore {

    private final ConcurrentHashMap<Long, UserLocation> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> userIdToId = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public UserLocation save(UserLocation entity) {
        Instant now = Instant.now();
        if (entity.getUserId() != null) {
            Long existing = userIdToId.get(entity.getUserId());
            if (existing != null) {
                entity.setId(existing);
            }
        }
        if (entity.getId() == null) {
            long id = seq.getAndIncrement();
            entity.setId(id);
            if (entity.getUserId() != null) {
                userIdToId.put(entity.getUserId(), id);
            }
        } else if (entity.getUserId() != null) {
            userIdToId.put(entity.getUserId(), entity.getId());
        }
        entity.setUpdatedAt(now);
        byId.put(entity.getId(), entity);
        return entity;
    }

    public Optional<UserLocation> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<UserLocation> findAll() {
        return new ArrayList<>(byId.values());
    }

    public List<UserLocation> findAllByOrderByUpdatedAtDesc() {
        return byId.values().stream()
                .sorted(Comparator.comparing(UserLocation::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    public Optional<UserLocation> findByUserId(String userId) {
        Long id = userIdToId.get(userId);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public boolean existsById(Long id) {
        return byId.containsKey(id);
    }

    public void deleteById(Long id) {
        UserLocation removed = byId.remove(id);
        if (removed != null && removed.getUserId() != null) {
            userIdToId.remove(removed.getUserId(), id);
        }
    }

    public void delete(UserLocation entity) {
        if (entity != null && entity.getId() != null) {
            deleteById(entity.getId());
        }
    }

    public void deleteByUserIdIn(List<String> userIds) {
        if (userIds == null) {
            return;
        }
        for (String userId : userIds) {
            findByUserId(userId).ifPresent(this::delete);
        }
    }

    public void deleteAll() {
        byId.clear();
        userIdToId.clear();
    }

    public long count() {
        return byId.size();
    }
}

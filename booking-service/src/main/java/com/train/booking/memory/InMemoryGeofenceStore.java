package com.train.booking.memory;

import com.train.booking.domain.Geofence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 2 — in-memory {@link Geofence} registry (station circles for Haversine in Layer 2).
 */
@Component
public class InMemoryGeofenceStore {

    private final ConcurrentHashMap<Long, Geofence> byId = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public Geofence save(Geofence entity) {
        if (entity.getId() == null) {
            entity.setId(seq.getAndIncrement());
        }
        byId.put(entity.getId(), entity);
        return entity;
    }

    public Optional<Geofence> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Geofence> findAll() {
        return new ArrayList<>(byId.values());
    }

    public List<Geofence> findAllByOrderByNameAsc() {
        return byId.values().stream()
                .sorted(Comparator.comparing(Geofence::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    public Optional<Geofence> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(g -> name.equalsIgnoreCase(g.getName()))
                .findFirst();
    }

    public Optional<Geofence> findByStationName(String stationName) {
        if (stationName == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(g -> stationName.equalsIgnoreCase(g.getStationName()))
                .findFirst();
    }

    public List<Geofence> findAllByStationName(String stationName) {
        if (stationName == null) {
            return List.of();
        }
        return byId.values().stream()
                .filter(g -> stationName.equalsIgnoreCase(g.getStationName()))
                .toList();
    }

    public boolean existsById(Long id) {
        return byId.containsKey(id);
    }

    public void deleteById(Long id) {
        byId.remove(id);
    }

    public void delete(Geofence entity) {
        if (entity != null && entity.getId() != null) {
            deleteById(entity.getId());
        }
    }

    public void deleteAll() {
        byId.clear();
    }

    public long count() {
        return byId.size();
    }
}

package com.train.booking.movement.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.train.booking.movement.stream.PassengerMovementState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PassengerMovementSnapshotService {

    @Value("${booking.movement.snapshot-interval-events:100}")
    private int snapshotIntervalEvents;

    private final PassengerMovementSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void maybeSnapshot(String userId, String lastEventId, PassengerMovementState state) {
        if (userId == null || lastEventId == null || state == null) return;
        PassengerMovementSnapshot snap = repository.findById(userId).orElseGet(() ->
            PassengerMovementSnapshot.builder()
                .userId(userId)
                .eventCount(0)
                .lastEventId(lastEventId)
                .stateJson("{}")
                .snapshotAt(Instant.now())
                .build()
        );
        long next = snap.getEventCount() + 1;
        snap.setEventCount(next);
        if (snapshotIntervalEvents > 0 && next % snapshotIntervalEvents != 0) {
            repository.save(snap);
            return;
        }
        try {
            snap.setStateJson(objectMapper.writeValueAsString(state));
            snap.setLastEventId(lastEventId);
            snap.setSnapshotAt(Instant.now());
            repository.save(snap);
        } catch (Exception ignored) {
            // Non-blocking: snapshot failures must not break movement processing.
        }
    }

    public Optional<PassengerMovementState> loadSnapshotState(String userId) {
        return repository.findById(userId).flatMap(s -> {
            try {
                return Optional.of(objectMapper.readValue(s.getStateJson(), PassengerMovementState.class));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }
}


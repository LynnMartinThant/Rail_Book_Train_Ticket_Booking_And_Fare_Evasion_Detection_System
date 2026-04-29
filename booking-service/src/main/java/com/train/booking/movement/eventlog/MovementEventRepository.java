package com.train.booking.movement.eventlog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MovementEventRepository extends JpaRepository<MovementEventEntity, String> {
    Page<MovementEventEntity> findByUserIdOrderByRecordedAtAsc(String userId, Pageable pageable);
    Page<MovementEventEntity> findByUserIdOrderByRecordedAtDesc(String userId, Pageable pageable);
    Page<MovementEventEntity> findByCorrelationIdOrderByRecordedAtAsc(String correlationId, Pageable pageable);
    Page<MovementEventEntity> findAllByOrderByRecordedAtDesc(Pageable pageable);
    Optional<MovementEventEntity> findFirstByOrderByRecordedAtDesc();

    @Query("SELECT e FROM MovementEventEntity e WHERE e.eventType IN :types ORDER BY e.recordedAt DESC")
    List<MovementEventEntity> findByEventTypeInOrderByRecordedAtDesc(@Param("types") List<MovementEventType> types, Pageable pageable);

    void deleteByUserIdIn(List<String> userIds);
}


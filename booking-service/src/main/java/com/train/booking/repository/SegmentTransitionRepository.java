package com.train.booking.repository;

import com.train.booking.domain.SegmentTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SegmentTransitionRepository extends JpaRepository<SegmentTransition, UUID> {
    List<SegmentTransition> findBySegmentIdOrderByOccurredAtAsc(Long segmentId);
    List<SegmentTransition> findByCorrelationIdOrderByOccurredAtAsc(String correlationId);
    List<SegmentTransition> findBySegmentIdInOrderByOccurredAtAsc(List<Long> segmentIds);
    void deleteBySegmentIdIn(List<Long> segmentIds);
}


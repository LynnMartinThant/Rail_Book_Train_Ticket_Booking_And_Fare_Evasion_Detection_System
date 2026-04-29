package com.train.booking.repository;

import com.train.booking.domain.RecomputationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecomputationRecordRepository extends JpaRepository<RecomputationRecord, UUID> {
    List<RecomputationRecord> findBySegmentIdOrderByRecomputedAtDesc(Long segmentId);
    List<RecomputationRecord> findByDisputeIdOrderByRecomputedAtDesc(UUID disputeId);
    void deleteBySegmentIdIn(List<Long> segmentIds);
}


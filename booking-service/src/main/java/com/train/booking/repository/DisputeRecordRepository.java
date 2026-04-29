package com.train.booking.repository;

import com.train.booking.domain.DisputeRecord;
import com.train.booking.domain.DisputeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DisputeRecordRepository extends JpaRepository<DisputeRecord, UUID> {
    List<DisputeRecord> findBySegmentIdOrderBySubmittedAtDesc(Long segmentId);
    List<DisputeRecord> findByUserIdOrderBySubmittedAtDesc(String userId);
    List<DisputeRecord> findByStatusOrderBySubmittedAtDesc(DisputeStatus status);
    void deleteBySegmentIdIn(List<Long> segmentIds);
    void deleteByUserIdIn(List<String> userIds);
}


package com.train.booking.repository;

import com.train.booking.domain.AuditDecisionRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditDecisionRecordRepository extends JpaRepository<AuditDecisionRecord, Long> {

    List<AuditDecisionRecord> findByUserIdOrderByRecordedAtDesc(String userId, Pageable pageable);
}

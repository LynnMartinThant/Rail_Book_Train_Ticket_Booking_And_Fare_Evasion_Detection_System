package com.train.booking.service;

import com.train.booking.domain.AuditLog;
import com.train.booking.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    private static final int AUDIT_PAGE_SIZE = 500;

    public void log(String userId, String action, String details) {
        String safeDetails = details != null && details.length() > 1024 ? details.substring(0, 1024) : details;
        auditLogRepository.save(AuditLog.builder()
            .userId(userId)
            .action(action)
            .details(safeDetails)
            .build());
    }

    public List<AuditLog> findByUserId(String userId, int limit) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
    }

    public List<AuditLog> findAll(int limit) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    public List<AuditLog> findByAction(String action, int limit) {
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action, PageRequest.of(0, limit));
    }
}

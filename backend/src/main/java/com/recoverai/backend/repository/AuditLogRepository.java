package com.recoverai.backend.repository;

import com.recoverai.backend.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByRecoveryCaseIdOrderByTimestampAsc(Long recoveryCaseId);
}

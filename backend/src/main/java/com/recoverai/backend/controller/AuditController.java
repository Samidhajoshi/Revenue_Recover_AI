package com.recoverai.backend.controller;

import com.recoverai.backend.entity.AuditLog;
import com.recoverai.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping("/{recoveryCaseId}")
    public List<AuditLog> byCase(@PathVariable Long recoveryCaseId) {
        return auditLogRepository.findByRecoveryCaseIdOrderByTimestampAsc(recoveryCaseId);
    }
}

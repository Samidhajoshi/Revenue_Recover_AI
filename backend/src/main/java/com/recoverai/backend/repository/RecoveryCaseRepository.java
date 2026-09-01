package com.recoverai.backend.repository;

import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.entity.enums.RecoveryState;
import com.recoverai.backend.entity.enums.RiskTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryCaseRepository extends JpaRepository<RecoveryCase, Long> {
    List<RecoveryCase> findByCurrentState(RecoveryState state);
    List<RecoveryCase> findByEntityTypeAndEntityId(EntityType entityType, String entityId);
    Optional<RecoveryCase> findFirstByEntityTypeAndEntityIdOrderByCreatedAtDesc(EntityType entityType, String entityId);
    List<RecoveryCase> findByEntityType(EntityType entityType);
    List<RecoveryCase> findByRiskTier(RiskTier riskTier);
    List<RecoveryCase> findByCurrentStateIn(List<RecoveryState> states);
}

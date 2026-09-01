package com.recoverai.backend.repository;

import com.recoverai.backend.entity.ActionEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActionEvaluationRepository extends JpaRepository<ActionEvaluation, Long> {
    List<ActionEvaluation> findByRecoveryCaseIdOrderByRankPositionAsc(Long recoveryCaseId);

    Optional<ActionEvaluation> findByRecoveryCaseIdAndSelectedTrue(Long recoveryCaseId);

    void deleteByRecoveryCaseId(Long recoveryCaseId);

    long countByPolicyAllowedTrue();

    long countByPolicyAllowedFalse();

    long countBySelectedTrue();
}

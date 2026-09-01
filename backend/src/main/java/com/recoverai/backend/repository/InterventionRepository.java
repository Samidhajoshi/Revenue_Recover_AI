package com.recoverai.backend.repository;

import com.recoverai.backend.entity.Intervention;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterventionRepository extends JpaRepository<Intervention, Long> {
    List<Intervention> findByRecoveryCaseIdOrderByExecutedAtAsc(Long recoveryCaseId);
}

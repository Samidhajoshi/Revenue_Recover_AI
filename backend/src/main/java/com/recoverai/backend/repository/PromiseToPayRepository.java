package com.recoverai.backend.repository;

import com.recoverai.backend.entity.PromiseToPay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromiseToPayRepository extends JpaRepository<PromiseToPay, Long> {
    List<PromiseToPay> findByRecoveryCaseId(Long recoveryCaseId);
}

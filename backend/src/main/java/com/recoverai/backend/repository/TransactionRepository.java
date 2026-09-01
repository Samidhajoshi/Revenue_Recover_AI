package com.recoverai.backend.repository;

import com.recoverai.backend.entity.Transaction;
import com.recoverai.backend.entity.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByStatus(TransactionStatus status);
    List<Transaction> findByGateway(String gateway);
}

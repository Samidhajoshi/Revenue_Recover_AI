package com.recoverai.backend.repository;

import com.recoverai.backend.entity.Transaction;
import com.recoverai.backend.entity.enums.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByStatus(TransactionStatus status);
    List<Transaction> findByGateway(String gateway);

    /**
     * Aggregated (status, dimensions) counts across every transaction that has
     * all four dimensions set - the dataset ml-service's /diagnose groups to
     * find which segment fails above baseline. Each row: [status, bank,
     * paymentMethod, region, gateway, count].
     */
    @Query("SELECT t.status, t.bank, t.paymentMethod, t.region, t.gateway, COUNT(t) "
            + "FROM Transaction t "
            + "WHERE t.bank IS NOT NULL AND t.paymentMethod IS NOT NULL "
            + "AND t.region IS NOT NULL AND t.gateway IS NOT NULL "
            + "GROUP BY t.status, t.bank, t.paymentMethod, t.region, t.gateway")
    List<Object[]> aggregateByStatusAndDimensions();
}

package com.recoverai.backend.service;

import com.recoverai.backend.config.PolicyProperties;
import com.recoverai.backend.entity.Gateway;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.Subscription;
import com.recoverai.backend.entity.Transaction;
import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.entity.enums.RecoveryState;
import com.recoverai.backend.entity.enums.SubscriptionStatus;
import com.recoverai.backend.entity.enums.TransactionStatus;
import com.recoverai.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 2 - scans imported transactions/subscriptions/gateways and creates
 * RecoveryCase rows (state = DETECTED) for at-risk entities: failed
 * payments, failed subscription renewals, and gateways whose current
 * failure rate is meaningfully above baseline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DetectionService {

    private final TransactionRepository transactionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final GatewayRepository gatewayRepository;
    private final RecoveryCaseRepository recoveryCaseRepository;
    private final PolicyProperties policyProperties;

    /** How much higher than baseline (relative) a gateway's failure rate must be to count as degraded. */
    private static final double GATEWAY_DEGRADATION_RATIO = 1.5;
    // Gateway rates are stored as 0-1 fractions (see CsvImportService#asFraction); 0.02 = a 2-percentage-point jump.
    private static final double GATEWAY_DEGRADATION_MIN_DELTA = 0.02;

    public int detectAll() {
        int created = 0;
        created += detectFailedPayments();
        created += detectFailedSubscriptions();
        created += detectDegradedGateways();
        return created;
    }

    public int detectFailedPayments() {
        int created = 0;
        List<Transaction> failed = transactionRepository.findByStatus(TransactionStatus.FAILED);
        for (Transaction tx : failed) {
            if (recoveryCaseRepository.findByEntityTypeAndEntityId(EntityType.PAYMENT, tx.getId()).isEmpty()) {
                RecoveryCase rc = RecoveryCase.builder()
                        .entityType(EntityType.PAYMENT)
                        .entityId(tx.getId())
                        .customerId(tx.getCustomerId())
                        .amountAtRisk(tx.getAmount())
                        .currentState(RecoveryState.DETECTED)
                        .currentAttempt(0)
                        .maxAttempts(policyProperties.getMaxRetries())
                        .recoveredAmount(0.0)
                        .build();
                recoveryCaseRepository.save(rc);
                created++;
            }
        }
        return created;
    }

    public int detectFailedSubscriptions() {
        int created = 0;
        List<Subscription> failed = subscriptionRepository.findByStatus(SubscriptionStatus.FAILED);
        for (Subscription sub : failed) {
            if (recoveryCaseRepository.findByEntityTypeAndEntityId(EntityType.SUBSCRIPTION, sub.getId()).isEmpty()) {
                RecoveryCase rc = RecoveryCase.builder()
                        .entityType(EntityType.SUBSCRIPTION)
                        .entityId(sub.getId())
                        .customerId(sub.getCustomerId())
                        .amountAtRisk(sub.getAmount())
                        .currentState(RecoveryState.DETECTED)
                        .currentAttempt(0)
                        .maxAttempts(policyProperties.getMaxRetries())
                        .recoveredAmount(0.0)
                        .build();
                recoveryCaseRepository.save(rc);
                created++;
            }
        }
        return created;
    }

    public int detectDegradedGateways() {
        int created = 0;
        List<Gateway> gateways = gatewayRepository.findAll();
        for (Gateway gw : gateways) {
            if (gw.getFailureRate() == null || gw.getBaselineFailureRate() == null) continue;
            double baseline = gw.getBaselineFailureRate();
            double current = gw.getFailureRate();
            boolean degraded = baseline > 0
                    ? (current >= baseline * GATEWAY_DEGRADATION_RATIO && (current - baseline) >= GATEWAY_DEGRADATION_MIN_DELTA)
                    : current >= GATEWAY_DEGRADATION_MIN_DELTA;
            if (!degraded) continue;
            if (recoveryCaseRepository.findByEntityTypeAndEntityId(EntityType.GATEWAY, gw.getId()).isEmpty()) {
                // Transactions reference the gateway by its display name (e.g. "Gateway A"), not its id
                // (e.g. "GW1") - match on whichever the imported transaction data actually used.
                double atRiskAmount = transactionRepository.findAll().stream()
                        .filter(t -> t.getStatus() == TransactionStatus.FAILED)
                        .filter(t -> gw.getId().equalsIgnoreCase(t.getGateway())
                                || (gw.getName() != null && gw.getName().equalsIgnoreCase(t.getGateway())))
                        .mapToDouble(t -> t.getAmount() == null ? 0 : t.getAmount())
                        .sum();
                if (atRiskAmount <= 0) atRiskAmount = 10000.0;
                RecoveryCase rc = RecoveryCase.builder()
                        .entityType(EntityType.GATEWAY)
                        .entityId(gw.getId())
                        .customerId(null)
                        .amountAtRisk(atRiskAmount)
                        .currentState(RecoveryState.DETECTED)
                        .currentAttempt(0)
                        // A single failed reroute attempt shouldn't immediately escalate a
                        // high-value gateway issue - allow one retry before giving up.
                        .maxAttempts(2)
                        .recoveredAmount(0.0)
                        .build();
                recoveryCaseRepository.save(rc);
                created++;
            }
        }
        return created;
    }
}

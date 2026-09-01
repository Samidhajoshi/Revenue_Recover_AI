package com.recoverai.backend.service;

import com.recoverai.backend.config.PolicyProperties;
import com.recoverai.backend.dto.CaseRunResult;
import com.recoverai.backend.dto.PolicyContext;
import com.recoverai.backend.dto.PolicyDecision;
import com.recoverai.backend.dto.ml.*;
import com.recoverai.backend.entity.*;
import com.recoverai.backend.entity.enums.*;
import com.recoverai.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The core agent orchestration loop (section 14):
 *
 *  event -> create recovery case -> calculate risk -> diagnose ->
 *  generate possible actions -> rank -> policy validation ->
 *  execute best allowed action -> record result -> did it recover?
 *    YES -> RECOVERED
 *    NO  -> more actions allowed? YES -> next cycle | NO -> STOP/ESCALATE
 *
 * Every state change goes through StateMachineService so nothing bypasses
 * the state machine or the audit trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestratorService {

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final TransactionRepository transactionRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final GatewayRepository gatewayRepository;
    private final CustomerRepository customerRepository;
    private final InterventionRepository interventionRepository;
    private final StateMachineService stateMachineService;
    private final PolicyEngine policyEngine;
    private final MlClientService mlClientService;
    private final RecoverySimulatorService simulatorService;
    private final SimClockService simClockService;
    private final PolicyProperties policyProperties;
    private final CounterfactualEvaluationService counterfactualEvaluationService;

    private static final String ACTOR_AGENT = "AGENT";

    // =====================================================================
    // PUBLIC API
    // =====================================================================

    /** Risk + diagnosis only, no execution. Idempotent once past DETECTED. */
    @Transactional
    public RecoveryCase analyze(Long caseId) {
        RecoveryCase rc = getCase(caseId);
        if (rc.getCurrentState() == RecoveryState.DETECTED) {
            rc = diagnoseCase(rc);
        }
        return rc;
    }

    /** Execute one decided action (one pass of decide -> policy -> execute -> observe). Does not loop. */
    @Transactional
    public RecoveryCase executeDecided(Long caseId) {
        RecoveryCase rc = getCase(caseId);
        if (rc.getCurrentState() == RecoveryState.DETECTED) {
            rc = diagnoseCase(rc);
        }
        if (rc.getCurrentState() == RecoveryState.DIAGNOSED || rc.getCurrentState() == RecoveryState.NEXT_INTERVENTION) {
            rc = runOneCycle(rc);
        }
        return rc;
    }

    /** Run a single case through the full agent loop until it reaches a terminal or waiting state. */
    @Transactional
    public CaseRunResult runCase(Long caseId) {
        RecoveryCase rc = getCase(caseId);

        if (stateMachineService.isTerminal(rc.getCurrentState())) {
            return toResult(rc, "Case already in terminal state " + rc.getCurrentState());
        }

        if (rc.getCurrentState() == RecoveryState.DETECTED) {
            rc = diagnoseCase(rc);
        }

        int safety = 0;
        while ((rc.getCurrentState() == RecoveryState.DIAGNOSED || rc.getCurrentState() == RecoveryState.NEXT_INTERVENTION)
                && safety < (rc.getMaxAttempts() == null ? 5 : rc.getMaxAttempts()) + 3) {
            rc = runOneCycle(rc);
            safety++;
        }

        return toResult(rc, "Processed to state " + rc.getCurrentState());
    }

    public List<RecoveryCase> allDetectedCases() {
        return recoveryCaseRepository.findByCurrentState(RecoveryState.DETECTED);
    }

    /**
     * Any case sitting in WAITING got there because a message was deferred by
     * quiet hours (a normal successful/failed intervention always advances
     * past WAITING within runOneCycle). Called after the simulated clock is
     * advanced so those cases can be re-evaluated against the new time.
     */
    @Transactional
    public List<CaseRunResult> resumeWaitingCases() {
        List<RecoveryCase> waiting = recoveryCaseRepository.findByCurrentState(RecoveryState.WAITING);
        List<CaseRunResult> results = new ArrayList<>();
        for (RecoveryCase rc : waiting) {
            RecoveryCase updated = runOneCycle(rc);
            int safety = 0;
            while (updated.getCurrentState() == RecoveryState.NEXT_INTERVENTION && safety < 5) {
                updated = runOneCycle(updated);
                safety++;
            }
            results.add(toResult(updated, "Resumed from WAITING -> " + updated.getCurrentState()));
        }
        return results;
    }

    // =====================================================================
    // DIAGNOSIS
    // =====================================================================

    private RecoveryCase diagnoseCase(RecoveryCase rc) {
        String eventType = switch (rc.getEntityType()) {
            case PAYMENT -> "PAYMENT_FAILED";
            case SUBSCRIPTION -> "SUBSCRIPTION_RENEWAL_FAILED";
            case GATEWAY -> "GATEWAY_DEGRADATION";
        };
        rc = stateMachineService.transition(rc, RecoveryState.DIAGNOSING, eventType, ACTOR_AGENT,
                "Detected at-risk " + rc.getEntityType() + " entity " + rc.getEntityId(), null);

        String failureReason = resolveFailureReason(rc);

        // ---- risk scoring ----
        Customer customer = rc.getCustomerId() == null ? null : customerRepository.findById(rc.getCustomerId()).orElse(null);
        RiskResponse risk = mlClientService.risk(RiskRequest.builder()
                .amount(rc.getAmountAtRisk())
                .previousFailures(customer == null ? 0 : customer.getFailedPayments())
                .successfulPayments(customer == null ? 0 : customer.getSuccessfulPayments())
                .customerLtv(customer == null ? 0 : customer.getLtv())
                .failureReason(failureReason)
                .build());
        rc.setRiskScore(risk.getRiskScore());
        try {
            rc.setRiskTier(RiskTier.valueOf(risk.getRiskTier() == null ? "MEDIUM" : risk.getRiskTier().toUpperCase()));
        } catch (Exception e) {
            rc.setRiskTier(RiskTier.MEDIUM);
        }
        rc.setRecoveryProbability(risk.getRecoveryProbability());

        // ---- root-cause diagnosis ----
        String diagnosisText;
        if (rc.getEntityType() == EntityType.GATEWAY) {
            Gateway gw = gatewayRepository.findById(rc.getEntityId()).orElse(null);
            GatewayAnomalyResponse anomaly = mlClientService.gatewayAnomaly(GatewayAnomalyRequest.builder()
                    .gatewayId(rc.getEntityId())
                    .currentFailureRate(gw == null ? null : gw.getFailureRate())
                    .baselineFailureRate(gw == null ? null : gw.getBaselineFailureRate())
                    .build());
            diagnosisText = "Gateway " + rc.getEntityId() + " failure rate " + asPercent(gw == null ? null : gw.getFailureRate())
                    + "% vs baseline " + asPercent(gw == null ? null : gw.getBaselineFailureRate())
                    + "% - severity " + anomaly.getSeverity() + ". " + anomaly.getRecommendation();
        } else {
            Transaction tx = rc.getEntityType() == EntityType.PAYMENT
                    ? transactionRepository.findById(rc.getEntityId()).orElse(null)
                    : null;
            DiagnoseResponse diag = mlClientService.diagnose(DiagnoseRequest.builder()
                    .entityType(rc.getEntityType().name())
                    .failureReason(failureReason)
                    .bank(tx == null ? null : tx.getBank())
                    .paymentMethod(tx == null ? null : tx.getPaymentMethod())
                    .region(tx == null ? null : tx.getRegion())
                    .gateway(tx == null ? null : tx.getGateway())
                    .build());
            diagnosisText = diag.getDiagnosis() + " (root cause path: " + diag.getRootCause() + ")";
        }
        rc.setDiagnosis(diagnosisText);
        recoveryCaseRepository.save(rc);

        rc = stateMachineService.transition(rc, RecoveryState.DIAGNOSED, "DIAGNOSIS_COMPLETED", ACTOR_AGENT,
                diagnosisText, Map.of(
                        "riskScore", rc.getRiskScore(),
                        "riskTier", rc.getRiskTier(),
                        "recoveryProbability", rc.getRecoveryProbability()));
        return rc;
    }

    // =====================================================================
    // ONE DECIDE -> POLICY -> EXECUTE -> OBSERVE CYCLE
    // =====================================================================

    private RecoveryCase runOneCycle(RecoveryCase rc) {
        String failureReason = resolveFailureReason(rc);

        // ---- DECIDING: counterfactual evaluation (generate + estimate + policy-filter + rank) ----
        rc = stateMachineService.transition(rc, RecoveryState.DECIDING, "COUNTERFACTUAL_EVALUATION_STARTED", ACTOR_AGENT,
                "Evaluating candidate actions for failure reason " + failureReason, null);

        List<ActionEvaluation> evaluations = counterfactualEvaluationService.evaluate(rc, failureReason);
        Optional<ActionEvaluation> selectedEvaluation = evaluations.stream()
                .filter(e -> Boolean.TRUE.equals(e.getSelected())).findFirst();

        if (selectedEvaluation.isEmpty()) {
            // Every candidate action was policy-blocked - nothing is safe to execute.
            boolean highValue = rc.getAmountAtRisk() != null && rc.getAmountAtRisk() >= policyProperties.getHighValueThreshold();
            rc = stateMachineService.transition(rc, highValue ? RecoveryState.ESCALATED : RecoveryState.STOPPED,
                    highValue ? "HUMAN_ESCALATION" : "WORKFLOW_STOPPED", "POLICY_ENGINE",
                    "All candidate actions were policy-blocked; nothing safe to execute.", null);
            counterfactualEvaluationService.recordActualOutcome(rc.getId(), 0.0);
            return rc;
        }
        InterventionType proposed = selectedEvaluation.get().getAction();

        // ---- POLICY_CHECK (final approval pass on the already-vetted selection) ----
        Customer customer = rc.getCustomerId() == null ? null : customerRepository.findById(rc.getCustomerId()).orElse(null);
        boolean disputed = "DISPUTED".equalsIgnoreCase(failureReason);

        PolicyContext ctx = PolicyContext.builder()
                .failureReason(failureReason)
                .retryCount(rc.getCurrentAttempt())
                .optedOut(customer != null && Boolean.TRUE.equals(customer.getOptedOut()))
                .disputed(disputed)
                .alreadySucceeded(false)
                .amountAtRisk(rc.getAmountAtRisk())
                .recoveryProbability(rc.getRecoveryProbability())
                .proposedAction(proposed)
                .currentTime(simClockService.now())
                .build();

        PolicyDecision decision = policyEngine.evaluate(ctx);

        rc = stateMachineService.transition(rc, RecoveryState.POLICY_CHECK, "POLICY_EVALUATED", "POLICY_ENGINE",
                decision.getReason(), Map.of("decision", decision.getReasonCode(), "allowed", decision.isAllowed()));

        if (decision.isEscalate()) {
            rc = stateMachineService.transition(rc, RecoveryState.ESCALATED, "HUMAN_ESCALATION", "POLICY_ENGINE",
                    decision.getReason(), null);
            counterfactualEvaluationService.recordActualOutcome(rc.getId(), 0.0);
            return rc;
        }
        if (decision.isStop()) {
            rc = stateMachineService.transition(rc, RecoveryState.STOPPED, "WORKFLOW_STOPPED", "POLICY_ENGINE",
                    decision.getReason(), null);
            counterfactualEvaluationService.recordActualOutcome(rc.getId(), 0.0);
            return rc;
        }
        if (decision.isDeferred()) {
            rc = stateMachineService.transition(rc, RecoveryState.WAITING, "MESSAGE_DEFERRED_QUIET_HOURS", "POLICY_ENGINE",
                    decision.getReason(), null);
            rc.setSelectedAction(decision.getEnforcedAction());
            rc.setWaitingUntil(null);
            return recoveryCaseRepository.save(rc);
        }

        InterventionType finalAction = decision.getEnforcedAction() != null ? decision.getEnforcedAction() : proposed;
        rc.setSelectedAction(finalAction);
        recoveryCaseRepository.save(rc);

        rc = stateMachineService.transition(rc, RecoveryState.INTERVENING, "POLICY_APPROVED", "POLICY_ENGINE",
                decision.getReason(), Map.of("enforcedAction", finalAction));

        // ---- EXECUTE (via simulator) ----
        int attemptNumber = rc.getCurrentAttempt() == null ? 1 : rc.getCurrentAttempt() + 1;
        double expectedProbability = rc.getEntityType() == EntityType.GATEWAY
                ? simulatorService.gatewayRerouteSuccessRate()
                : simulatorService.successProbability(failureReason, finalAction);

        Intervention intervention = Intervention.builder()
                .recoveryCaseId(rc.getId())
                .type(finalAction)
                .attemptNumber(attemptNumber)
                .reason(decision.getReason())
                .status(InterventionStatus.EXECUTED)
                .expectedRecoveryProbability(expectedProbability)
                .executedAt(simClockService.now())
                .build();
        interventionRepository.save(intervention);

        rc.setCurrentAttempt(attemptNumber);
        recoveryCaseRepository.save(rc);

        rc = stateMachineService.transition(rc, RecoveryState.WAITING, "ACTION_EXECUTED", ACTOR_AGENT,
                "Executed " + finalAction + " (attempt " + attemptNumber + ")",
                Map.of("interventionId", intervention.getId(), "expectedProbability", expectedProbability));

        // ---- OBSERVE OUTCOME (simulated synchronously for the demo) ----
        boolean success = rc.getEntityType() == EntityType.GATEWAY
                ? simulatorService.simulateGatewayReroute()
                : simulatorService.simulateOutcome(failureReason, finalAction);

        intervention.setStatus(success ? InterventionStatus.SUCCESS : InterventionStatus.FAILED);
        intervention.setResult(success ? "SUCCESS" : "FAILED");
        intervention.setCompletedAt(simClockService.now());
        interventionRepository.save(intervention);

        if (success) {
            rc.setRecoveredAmount(rc.getAmountAtRisk());
            recoveryCaseRepository.save(rc);
            rc = stateMachineService.transition(rc, RecoveryState.RECOVERED, "PAYMENT_SUCCESS", ACTOR_AGENT,
                    "Intervention " + finalAction + " succeeded; recovered " + rc.getAmountAtRisk(), null);
            stateMachineService.logEvent(rc, "RECOVERY_CONFIRMED", ACTOR_AGENT,
                    "Recovered amount: " + rc.getRecoveredAmount(), null);
            stateMachineService.logEvent(rc, "WORKFLOW_STOPPED", ACTOR_AGENT, "Case recovered; no further action.", null);
            counterfactualEvaluationService.recordActualOutcome(rc.getId(), rc.getRecoveredAmount());
            return rc;
        }

        boolean moreAttemptsAllowed = rc.getCurrentAttempt() < (rc.getMaxAttempts() == null ? policyProperties.getMaxRetries() : rc.getMaxAttempts());
        if (moreAttemptsAllowed) {
            rc = stateMachineService.transition(rc, RecoveryState.NEXT_INTERVENTION, "NEXT_ATTEMPT_QUEUED", ACTOR_AGENT,
                    "Intervention failed; " + (rc.getMaxAttempts() - rc.getCurrentAttempt()) + " attempt(s) remaining.", null);
            return rc;
        } else {
            boolean highValue = rc.getAmountAtRisk() != null && rc.getAmountAtRisk() >= policyProperties.getHighValueThreshold();
            if (highValue) {
                rc = stateMachineService.transition(rc, RecoveryState.ESCALATED, "HUMAN_ESCALATION", ACTOR_AGENT,
                        "Max attempts reached on high-value case; escalating to human.", null);
            } else {
                rc = stateMachineService.transition(rc, RecoveryState.STOPPED, "WORKFLOW_STOPPED", ACTOR_AGENT,
                        "Max attempts reached; stopping recovery workflow.", null);
            }
            counterfactualEvaluationService.recordActualOutcome(rc.getId(), 0.0);
            return rc;
        }
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private String asPercent(Double fraction) {
        return fraction == null ? "?" : String.valueOf(Math.round(fraction * 1000.0) / 10.0);
    }

    private RecoveryCase getCase(Long id) {
        return recoveryCaseRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("RecoveryCase not found: " + id));
    }

    private String resolveFailureReason(RecoveryCase rc) {
        return switch (rc.getEntityType()) {
            case PAYMENT -> transactionRepository.findById(rc.getEntityId())
                    .map(Transaction::getFailureReason).orElse("UNKNOWN");
            case SUBSCRIPTION -> subscriptionRepository.findById(rc.getEntityId())
                    .map(Subscription::getFailureReason).orElse("UNKNOWN");
            case GATEWAY -> "GATEWAY_FAILURE";
        };
    }

    private CaseRunResult toResult(RecoveryCase rc, String message) {
        return CaseRunResult.builder()
                .caseId(rc.getId())
                .finalState(rc.getCurrentState().name())
                .selectedAction(rc.getSelectedAction() == null ? null : rc.getSelectedAction().name())
                .recoveredAmount(rc.getRecoveredAmount())
                .attemptsUsed(rc.getCurrentAttempt())
                .message(message)
                .build();
    }
}

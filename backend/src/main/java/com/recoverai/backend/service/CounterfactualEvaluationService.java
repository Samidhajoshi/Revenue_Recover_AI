package com.recoverai.backend.service;

import com.recoverai.backend.dto.PolicyContext;
import com.recoverai.backend.dto.PolicyDecision;
import com.recoverai.backend.entity.ActionEvaluation;
import com.recoverai.backend.entity.Customer;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.entity.enums.InterventionType;
import com.recoverai.backend.repository.ActionEvaluationRepository;
import com.recoverai.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.recoverai.backend.entity.enums.InterventionType.*;

/**
 * Before executing anything, evaluates every plausible intervention for a
 * case, estimates its recovery probability and expected recovery, filters
 * out whatever policy prohibits, and ranks what's left. Only the winner is
 * ever executed - every other row here is a counterfactual: a recorded,
 * audited "what we didn't do, and why not."
 *
 * Reuses the existing PolicyEngine and RecoverySimulatorService probability
 * table rather than introducing a second model, per the plan this feature
 * was built from.
 */
@Service
@RequiredArgsConstructor
public class CounterfactualEvaluationService {

    private final ActionEvaluationRepository actionEvaluationRepository;
    private final RecoverySimulatorService simulatorService;
    private final PolicyEngine policyEngine;
    private final CustomerRepository customerRepository;
    private final SimClockService simClockService;
    private final StateMachineService stateMachineService;
    private final ActionRankingService actionRankingService;

    private static final String ACTOR = "AGENT";

    /** Which actions are even plausible for this case's failure reason - don't evaluate irrelevant ones. */
    public List<InterventionType> getCandidateActions(RecoveryCase rc, String failureReason) {
        if (rc.getEntityType() == EntityType.GATEWAY) {
            return List.of(CHANGE_GATEWAY, RETRY_PAYMENT, ESCALATE);
        }
        String reason = failureReason == null ? "" : failureReason.toUpperCase();
        return switch (reason) {
            case "DISPUTED" -> List.of(ESCALATE, STOP);
            default -> List.of(RETRY_PAYMENT, PAYMENT_LINK, SEND_MESSAGE, ESCALATE);
        };
    }

    public double estimateRecoveryProbability(RecoveryCase rc, InterventionType action, String failureReason) {
        if (rc.getEntityType() == EntityType.GATEWAY && action == CHANGE_GATEWAY) {
            return simulatorService.gatewayRerouteSuccessRate();
        }
        if (action == STOP) {
            return 0.0;
        }
        return simulatorService.successProbability(failureReason, action);
    }

    public double calculateExpectedRecovery(double amountAtRisk, double probability) {
        return amountAtRisk * probability;
    }

    /**
     * Evaluates every candidate action, policy-filters, ranks, and persists
     * one ActionEvaluation row per candidate (re-evaluating a case replaces
     * its prior evaluation rows). Marks exactly one row selected=true - the
     * caller is responsible for executing ONLY that action; nothing here
     * executes anything.
     */
    @Transactional
    public List<ActionEvaluation> evaluate(RecoveryCase rc, String failureReason) {
        actionEvaluationRepository.deleteByRecoveryCaseId(rc.getId());

        List<InterventionType> candidates = getCandidateActions(rc, failureReason);
        stateMachineService.logEvent(rc, "CANDIDATE_ACTIONS_GENERATED", ACTOR,
                "Generated " + candidates.size() + " candidate action(s) for failure reason " + failureReason,
                Map.of("candidates", candidates));

        Customer customer = rc.getCustomerId() == null ? null : customerRepository.findById(rc.getCustomerId()).orElse(null);
        boolean disputed = "DISPUTED".equalsIgnoreCase(failureReason);
        double amount = rc.getAmountAtRisk() == null ? 0 : rc.getAmountAtRisk();

        List<ActionEvaluation> evaluations = new ArrayList<>();
        for (InterventionType action : candidates) {
            double probability = estimateRecoveryProbability(rc, action, failureReason);
            double expectedRecovery = calculateExpectedRecovery(amount, probability);

            PolicyContext ctx = PolicyContext.builder()
                    .failureReason(failureReason)
                    .retryCount(rc.getCurrentAttempt())
                    .optedOut(customer != null && Boolean.TRUE.equals(customer.getOptedOut()))
                    .disputed(disputed)
                    .alreadySucceeded(false)
                    .amountAtRisk(amount)
                    .recoveryProbability(probability)
                    .proposedAction(action)
                    .currentTime(simClockService.now())
                    .build();
            PolicyDecision decision = policyEngine.evaluate(ctx);

            ActionEvaluation ev = ActionEvaluation.builder()
                    .recoveryCaseId(rc.getId())
                    .action(action)
                    .recoveryProbability(probability)
                    .amountAtRisk(amount)
                    .expectedRecovery(expectedRecovery)
                    .policyAllowed(decision.isAllowed())
                    .policyReason(decision.getReason())
                    .selected(false)
                    .build();
            evaluations.add(ev);

            stateMachineService.logEvent(rc, "ACTION_EVALUATED", ACTOR,
                    action + " probability=" + probability + " expectedRecovery=" + expectedRecovery,
                    Map.of("action", action, "probability", probability, "expectedRecovery", expectedRecovery));
            stateMachineService.logEvent(rc, "ACTION_POLICY_CHECKED", "POLICY_ENGINE",
                    decision.getReason(), Map.of("action", action, "allowed", decision.isAllowed()));
            if (!decision.isAllowed()) {
                stateMachineService.logEvent(rc, "ACTION_BLOCKED", "POLICY_ENGINE",
                        decision.getReasonCode(), Map.of("action", action, "reason", decision.getReasonCode()));
            }
        }

        actionRankingService.rank(evaluations);

        List<ActionEvaluation> saved = actionEvaluationRepository.saveAll(evaluations);
        saved.stream().filter(e -> Boolean.TRUE.equals(e.getSelected())).findFirst().ifPresent(best ->
                stateMachineService.logEvent(rc, "ACTION_SELECTED", ACTOR,
                        "Selected " + best.getAction() + " - highest expected recovery among policy-compliant actions.",
                        Map.of("action", best.getAction(), "expectedRecovery", best.getExpectedRecovery())));

        return saved;
    }

    public List<ActionEvaluation> getEvaluations(Long recoveryCaseId) {
        return actionEvaluationRepository.findByRecoveryCaseIdOrderByRankPositionAsc(recoveryCaseId);
    }

    /** Called once a case reaches RECOVERED/ESCALATED/STOPPED - fills in predicted-vs-actual on the selected row. */
    @Transactional
    public void recordActualOutcome(Long recoveryCaseId, double actualRecovery) {
        actionEvaluationRepository.findByRecoveryCaseIdAndSelectedTrue(recoveryCaseId).ifPresent(selected -> {
            selected.setActualRecovery(actualRecovery);
            double expected = selected.getExpectedRecovery() == null ? 0 : selected.getExpectedRecovery();
            selected.setPredictionError(actualRecovery - expected);
            actionEvaluationRepository.save(selected);
        });
    }
}

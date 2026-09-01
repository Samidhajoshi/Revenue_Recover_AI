package com.recoverai.backend.service;

import com.recoverai.backend.dto.BaselineComparison;
import com.recoverai.backend.dto.CounterfactualMetrics;
import com.recoverai.backend.dto.SimulationRunResult;
import com.recoverai.backend.entity.ActionEvaluation;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.Transaction;
import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.entity.enums.InterventionType;
import com.recoverai.backend.entity.enums.RecoveryState;
import com.recoverai.backend.repository.ActionEvaluationRepository;
import com.recoverai.backend.repository.InterventionRepository;
import com.recoverai.backend.repository.RecoveryCaseRepository;
import com.recoverai.backend.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Batch simulation entry point (POST /api/simulation/run, section 13/18):
 * runs detection + the full agent loop over every DETECTED case, then
 * compares the result against a naive baseline ("every failed payment gets
 * retried once, no diagnosis, no policy") to quantify the agent's
 * incremental value.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    private final DetectionService detectionService;
    private final AgentOrchestratorService orchestratorService;
    private final RecoveryCaseRepository recoveryCaseRepository;
    private final InterventionRepository interventionRepository;
    private final TransactionRepository transactionRepository;
    private final RecoverySimulatorService simulatorService;
    private final ActionEvaluationRepository actionEvaluationRepository;

    /**
     * Deliberately NOT @Transactional at this level. detectAll() and each
     * orchestratorService.runCase() call already have their own transaction
     * boundary - wrapping the whole batch in one outer transaction would join
     * every one of those into a single transaction spanning thousands of
     * cases, so Hibernate's persistence context never clears and every
     * flush/dirty-check gets slower than the last. Each case commits and
     * clears independently instead, keeping per-case cost roughly constant
     * across the whole batch.
     */
    public SimulationRunResult run() {
        detectionService.detectAll();

        List<RecoveryCase> toProcess = recoveryCaseRepository.findByCurrentState(RecoveryState.DETECTED);
        List<Long> processedIds = toProcess.stream().map(RecoveryCase::getId).toList();

        for (Long id : processedIds) {
            orchestratorService.runCase(id);
        }

        List<RecoveryCase> processed = recoveryCaseRepository.findAllById(processedIds);

        int casesProcessed = processed.size();
        int interventions = processed.stream()
                .mapToInt(c -> interventionRepository.findByRecoveryCaseIdOrderByExecutedAtAsc(c.getId()).size())
                .sum();
        double recoveredAmount = processed.stream()
                .filter(c -> c.getCurrentState() == RecoveryState.RECOVERED)
                .mapToDouble(c -> c.getRecoveredAmount() == null ? 0 : c.getRecoveredAmount())
                .sum();
        long recoveredCount = processed.stream().filter(c -> c.getCurrentState() == RecoveryState.RECOVERED).count();
        double recoveryRate = casesProcessed > 0 ? (double) recoveredCount / casesProcessed : 0.0;
        int escalations = (int) processed.stream().filter(c -> c.getCurrentState() == RecoveryState.ESCALATED).count();
        int safelyStopped = (int) processed.stream().filter(c -> c.getCurrentState() == RecoveryState.STOPPED).count();

        BaselineComparison baseline = computeBaseline(processed, recoveredAmount);
        CounterfactualMetrics counterfactuals = computeCounterfactualMetrics(processedIds, recoveredCount);

        return SimulationRunResult.builder()
                .casesProcessed(casesProcessed)
                .interventions(interventions)
                .recoveredAmount(round(recoveredAmount))
                .recoveryRate(round(recoveryRate))
                .escalations(escalations)
                .safelyStoppedCases(safelyStopped)
                .baseline(baseline)
                .counterfactuals(counterfactuals)
                .build();
    }

    /**
     * Metrics over the ActionEvaluation rows left behind by counterfactual
     * evaluation (section 21) - every candidate considered per case, not just
     * the one that executed.
     */
    private CounterfactualMetrics computeCounterfactualMetrics(List<Long> caseIds, long recoveredCount) {
        List<ActionEvaluation> all = caseIds.stream()
                .flatMap(id -> actionEvaluationRepository.findByRecoveryCaseIdOrderByRankPositionAsc(id).stream())
                .toList();

        long evaluated = all.size();
        long blocked = all.stream().filter(e -> !Boolean.TRUE.equals(e.getPolicyAllowed())).count();
        long compliant = evaluated - blocked;

        var selectedByType = all.stream()
                .filter(e -> Boolean.TRUE.equals(e.getSelected()))
                .collect(Collectors.groupingBy(e -> e.getAction().name(), Collectors.counting()));

        double totalExpected = all.stream()
                .filter(e -> Boolean.TRUE.equals(e.getSelected()))
                .mapToDouble(e -> e.getExpectedRecovery() == null ? 0 : e.getExpectedRecovery())
                .sum();
        double totalActual = all.stream()
                .filter(e -> e.getActualRecovery() != null)
                .mapToDouble(ActionEvaluation::getActualRecovery)
                .sum();

        double avgActionsPerCase = caseIds.isEmpty() ? 0 : (double) evaluated / caseIds.size();
        double avgInterventionsPerRecoveredCase = recoveredCount == 0 ? 0
                : caseIds.stream().mapToInt(id -> interventionRepository.findByRecoveryCaseIdOrderByExecutedAtAsc(id).size()).sum()
                        / (double) recoveredCount;

        return CounterfactualMetrics.builder()
                .candidateActionsEvaluated(evaluated)
                .policyBlockedActions(blocked)
                .policyCompliantActions(compliant)
                .selectedActionsByType(selectedByType)
                .totalExpectedRecovery(round(totalExpected))
                .totalActualRecovery(round(totalActual))
                .expectedVsActualDifference(round(totalActual - totalExpected))
                .averageActionsEvaluatedPerCase(round(avgActionsPerCase))
                .averageInterventionsPerRecoveredCase(round(avgInterventionsPerRecoveredCase))
                .build();
    }

    /**
     * Baseline strategy (section 18): every failed payment gets retried once,
     * with no diagnosis and no policy checks. Applied to the same set of
     * PAYMENT cases the agent just processed, so the comparison is apples to
     * apples.
     */
    private BaselineComparison computeBaseline(List<RecoveryCase> processed, double agentRecoveredAmount) {
        double baselineRecovered = 0.0;
        for (RecoveryCase rc : processed) {
            if (rc.getEntityType() != EntityType.PAYMENT) continue;
            Transaction tx = transactionRepository.findById(rc.getEntityId()).orElse(null);
            if (tx == null) continue;
            boolean success = simulatorService.simulateOutcome(tx.getFailureReason(), InterventionType.RETRY_PAYMENT);
            if (success) {
                baselineRecovered += tx.getAmount() == null ? 0 : tx.getAmount();
            }
        }
        double incremental = agentRecoveredAmount - baselineRecovered;
        double improvementPercent = baselineRecovered > 0 ? (incremental / baselineRecovered) * 100.0 : (agentRecoveredAmount > 0 ? 100.0 : 0.0);

        return BaselineComparison.builder()
                .baselineRecoveredAmount(round(baselineRecovered))
                .agentRecoveredAmount(round(agentRecoveredAmount))
                .incrementalRecovery(round(incremental))
                .improvementPercent(round(improvementPercent))
                .build();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

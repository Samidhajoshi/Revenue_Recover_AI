package com.recoverai.backend.controller;

import com.recoverai.backend.dto.ActionEvaluationResponse;
import com.recoverai.backend.dto.CounterfactualEvaluationResponse;
import com.recoverai.backend.entity.ActionEvaluation;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.Subscription;
import com.recoverai.backend.entity.Transaction;
import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.repository.RecoveryCaseRepository;
import com.recoverai.backend.repository.SubscriptionRepository;
import com.recoverai.backend.repository.TransactionRepository;
import com.recoverai.backend.service.CounterfactualEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Read-only view of the counterfactual evaluation for a case, plus an
 * explicit re-evaluate trigger. Never executes anything - see
 * CounterfactualEvaluationService for why.
 */
@RestController
@RequestMapping("/api/recovery")
@RequiredArgsConstructor
public class CounterfactualController {

    private final CounterfactualEvaluationService counterfactualEvaluationService;
    private final RecoveryCaseRepository recoveryCaseRepository;
    private final TransactionRepository transactionRepository;
    private final SubscriptionRepository subscriptionRepository;

    @GetMapping("/{id}/counterfactuals")
    public CounterfactualEvaluationResponse getCounterfactuals(@PathVariable Long id) {
        RecoveryCase rc = getCase(id);
        List<ActionEvaluation> evaluations = counterfactualEvaluationService.getEvaluations(id);
        if (evaluations.isEmpty()) {
            evaluations = counterfactualEvaluationService.evaluate(rc, resolveFailureReason(rc));
        }
        return toResponse(rc, evaluations);
    }

    @PostMapping("/{id}/evaluate")
    public CounterfactualEvaluationResponse reEvaluate(@PathVariable Long id) {
        RecoveryCase rc = getCase(id);
        List<ActionEvaluation> evaluations = counterfactualEvaluationService.evaluate(rc, resolveFailureReason(rc));
        return toResponse(rc, evaluations);
    }

    private CounterfactualEvaluationResponse toResponse(RecoveryCase rc, List<ActionEvaluation> evaluations) {
        String recommended = evaluations.stream()
                .filter(e -> Boolean.TRUE.equals(e.getSelected()))
                .map(e -> e.getAction().name())
                .findFirst().orElse(null);

        List<ActionEvaluationResponse> body = evaluations.stream()
                .sorted((a, b) -> {
                    Integer ra = a.getRankPosition() == null ? Integer.MAX_VALUE : a.getRankPosition();
                    Integer rb = b.getRankPosition() == null ? Integer.MAX_VALUE : b.getRankPosition();
                    return ra.compareTo(rb);
                })
                .map(e -> ActionEvaluationResponse.builder()
                        .action(e.getAction().name())
                        .probability(e.getRecoveryProbability())
                        .expectedRecovery(e.getExpectedRecovery())
                        .allowed(e.getPolicyAllowed())
                        .policyReason(e.getPolicyReason())
                        .selected(e.getSelected())
                        .rankPosition(e.getRankPosition())
                        .actualRecovery(e.getActualRecovery())
                        .predictionError(e.getPredictionError())
                        .build())
                .collect(Collectors.toList());

        return CounterfactualEvaluationResponse.builder()
                .caseId(rc.getId())
                .amountAtRisk(rc.getAmountAtRisk())
                .failureReason(resolveFailureReason(rc))
                .recommendedAction(recommended)
                .evaluations(body)
                .build();
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
}

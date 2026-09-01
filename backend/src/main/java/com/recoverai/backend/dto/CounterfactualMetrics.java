package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CounterfactualMetrics {
    private Long candidateActionsEvaluated;
    private Long policyBlockedActions;
    private Long policyCompliantActions;
    private Map<String, Long> selectedActionsByType;
    private Double totalExpectedRecovery;
    private Double totalActualRecovery;
    private Double expectedVsActualDifference;
    private Double averageActionsEvaluatedPerCase;
    private Double averageInterventionsPerRecoveredCase;
}

package com.recoverai.backend.service;

import com.recoverai.backend.entity.ActionEvaluation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ranks already-evaluated candidate actions and marks the winner. Only
 * policy-allowed actions are eligible - policy filtering must happen before
 * ranking, never after (section 8 of the counterfactual plan).
 */
@Service
public class ActionRankingService {

    /**
     * Sorts the policy-allowed subset by expected recovery (desc), assigns
     * rankPosition 1..N to that subset, and marks the top one selected.
     * Blocked actions are left unranked (rankPosition stays null) and never
     * selected. Mutates the ActionEvaluation objects passed in; returns the
     * same list for convenience.
     */
    public List<ActionEvaluation> rank(List<ActionEvaluation> evaluations) {
        List<ActionEvaluation> allowed = evaluations.stream()
                .filter(e -> Boolean.TRUE.equals(e.getPolicyAllowed()))
                .sorted((a, b) -> Double.compare(
                        b.getExpectedRecovery() == null ? 0 : b.getExpectedRecovery(),
                        a.getExpectedRecovery() == null ? 0 : a.getExpectedRecovery()))
                .collect(Collectors.toList());

        for (int i = 0; i < allowed.size(); i++) {
            allowed.get(i).setRankPosition(i + 1);
        }
        if (!allowed.isEmpty()) {
            allowed.get(0).setSelected(true);
        }
        return evaluations;
    }
}

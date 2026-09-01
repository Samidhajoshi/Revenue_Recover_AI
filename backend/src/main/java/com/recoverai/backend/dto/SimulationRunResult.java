package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SimulationRunResult {
    private Integer casesProcessed;
    private Integer interventions;
    private Double recoveredAmount;
    private Double recoveryRate;
    private Integer escalations;
    private Integer safelyStoppedCases;
    private BaselineComparison baseline;
    private CounterfactualMetrics counterfactuals;
}

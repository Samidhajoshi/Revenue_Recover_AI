package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BaselineComparison {
    private Double baselineRecoveredAmount;
    private Double agentRecoveredAmount;
    private Double incrementalRecovery;
    private Double improvementPercent;
}

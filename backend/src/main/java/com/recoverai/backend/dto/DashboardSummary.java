package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSummary {
    private Double revenueAtRisk;
    private Double recoverableRevenue;
    private Double recoveredRevenue;
    private Double recoveryRate;
    private Long activeCases;
    private Long escalations;
    private Long totalCases;
    private Long stoppedCases;
}

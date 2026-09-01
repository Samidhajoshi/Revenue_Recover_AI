package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecoveryByTypeRow {
    private String entityType;
    private Long casesCount;
    private Double amountAtRisk;
    private Double recoveredAmount;
    private Double recoveryRate;
}

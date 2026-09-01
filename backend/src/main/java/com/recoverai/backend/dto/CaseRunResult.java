package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CaseRunResult {
    private Long caseId;
    private String finalState;
    private String selectedAction;
    private Double recoveredAmount;
    private Integer attemptsUsed;
    private String message;
}

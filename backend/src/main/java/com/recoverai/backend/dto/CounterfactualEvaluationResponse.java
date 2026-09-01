package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CounterfactualEvaluationResponse {
    private Long caseId;
    private Double amountAtRisk;
    private String failureReason;
    private String recommendedAction;
    private List<ActionEvaluationResponse> evaluations;
}

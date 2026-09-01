package com.recoverai.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionEvaluationResponse {
    private String action;
    private Double probability;
    private Double expectedRecovery;
    private Boolean allowed;
    private String policyReason;
    private Boolean selected;
    private Integer rankPosition;
    private Double actualRecovery;
    private Double predictionError;
}

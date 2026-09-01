package com.recoverai.backend.dto;

import com.recoverai.backend.entity.enums.InterventionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Everything the PolicyEngine needs to evaluate a proposed action. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyContext {
    private String failureReason;
    private Integer retryCount;
    private Boolean optedOut;
    private Boolean disputed;
    private Boolean alreadySucceeded;
    private Double amountAtRisk;
    private Double recoveryProbability;
    private InterventionType proposedAction;
    private LocalDateTime currentTime;
}

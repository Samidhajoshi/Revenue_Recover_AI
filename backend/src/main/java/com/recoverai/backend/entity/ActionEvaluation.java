package com.recoverai.backend.entity;

import com.recoverai.backend.entity.enums.InterventionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One candidate action considered for a RecoveryCase during counterfactual
 * evaluation (DECIDING state) - what it was expected to recover, whether
 * policy allowed it, and whether it was the one actually selected. Rows for
 * actions that were NOT selected are never executed; they exist purely as an
 * auditable "here is what we didn't do, and why" record.
 */
@Entity
@Table(name = "action_evaluations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActionEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recoveryCaseId;

    @Enumerated(EnumType.STRING)
    private InterventionType action;

    private Double recoveryProbability;

    private Double amountAtRisk;

    private Double expectedRecovery;

    private Boolean policyAllowed;

    @Column(length = 500)
    private String policyReason;

    private Boolean selected;

    private Integer rankPosition;

    /** Filled in only for the selected action, once the case reaches a terminal state. */
    private Double actualRecovery;

    private Double predictionError;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (selected == null) selected = false;
        if (policyAllowed == null) policyAllowed = false;
    }
}

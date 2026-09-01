package com.recoverai.backend.entity;

import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.entity.enums.InterventionType;
import com.recoverai.backend.entity.enums.RecoveryState;
import com.recoverai.backend.entity.enums.RiskTier;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "recovery_cases")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecoveryCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private EntityType entityType;

    /** id of the Transaction / Subscription / Gateway this case refers to */
    private String entityId;

    private String customerId;

    private Double amountAtRisk;

    private Double riskScore;

    @Enumerated(EnumType.STRING)
    private RiskTier riskTier;

    @Column(length = 2000)
    private String diagnosis;

    private Double recoveryProbability;

    @Enumerated(EnumType.STRING)
    private RecoveryState currentState;

    private Integer currentAttempt;

    private Integer maxAttempts;

    @Enumerated(EnumType.STRING)
    private InterventionType selectedAction;

    private Double recoveredAmount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** when set, case is WAITING until this simulated timestamp */
    private LocalDateTime waitingUntil;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (currentAttempt == null) currentAttempt = 0;
        if (maxAttempts == null) maxAttempts = 3;
        if (recoveredAmount == null) recoveredAmount = 0.0;
        if (currentState == null) currentState = RecoveryState.DETECTED;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

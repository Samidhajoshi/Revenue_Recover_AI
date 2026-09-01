package com.recoverai.backend.entity;

import com.recoverai.backend.entity.enums.InterventionStatus;
import com.recoverai.backend.entity.enums.InterventionType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "interventions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Intervention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recoveryCaseId;

    @Enumerated(EnumType.STRING)
    private InterventionType type;

    private Integer attemptNumber;

    @Column(length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    private InterventionStatus status;

    private Double expectedRecoveryProbability;

    private LocalDateTime executedAt;

    private LocalDateTime completedAt;

    @Column(length = 1000)
    private String result;

    @PrePersist
    public void prePersist() {
        if (status == null) status = InterventionStatus.PENDING;
    }
}

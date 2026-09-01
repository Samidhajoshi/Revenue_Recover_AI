package com.recoverai.backend.entity;

import com.recoverai.backend.entity.enums.GatewayStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "gateways")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gateway {

    @Id
    private String id;

    private String name;

    private Double successRate;

    private Double failureRate;

    private Double baselineFailureRate;

    @Enumerated(EnumType.STRING)
    private GatewayStatus status;

    private Double costPerTransaction;

    private LocalDateTime lastUpdated;

    @PrePersist
    @PreUpdate
    public void touch() {
        lastUpdated = LocalDateTime.now();
        if (status == null) status = GatewayStatus.HEALTHY;
    }
}

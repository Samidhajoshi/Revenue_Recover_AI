package com.recoverai.backend.entity;

import com.recoverai.backend.entity.enums.SubscriptionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    private String id;

    private String customerId;

    private Double amount;

    private String billingCycle;

    private LocalDate nextPaymentDate;

    @Enumerated(EnumType.STRING)
    private SubscriptionStatus status;

    private String paymentMethod;

    private String failureReason;

    private Integer retryCount;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (retryCount == null) retryCount = 0;
    }
}

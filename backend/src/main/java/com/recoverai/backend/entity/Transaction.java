package com.recoverai.backend.entity;

import com.recoverai.backend.entity.enums.TransactionStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    private String id;

    private String customerId;

    private Double amount;

    private String currency;

    private String paymentMethod;

    private String gateway;

    private String bank;

    private String region;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private String failureReason;

    private Integer attemptNumber;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (attemptNumber == null) attemptNumber = 1;
        if (currency == null) currency = "INR";
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

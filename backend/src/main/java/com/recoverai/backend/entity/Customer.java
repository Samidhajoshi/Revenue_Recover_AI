package com.recoverai.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    private String id;

    private String name;

    private String email;

    private String phone;

    private Double ltv;

    private Integer totalPayments;

    private Integer successfulPayments;

    private Integer failedPayments;

    private String segment;

    private Boolean optedOut;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (optedOut == null) {
            optedOut = false;
        }
        if (totalPayments == null) totalPayments = 0;
        if (successfulPayments == null) successfulPayments = 0;
        if (failedPayments == null) failedPayments = 0;
    }
}

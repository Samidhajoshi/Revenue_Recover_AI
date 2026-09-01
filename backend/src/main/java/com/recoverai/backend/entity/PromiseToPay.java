package com.recoverai.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A customer's stated intent to pay by a future date, extracted from free
 * text by the LLM (section 11) and validated here before storage. This
 * record is informational only - it does not itself trigger any recovery
 * action; the LLM must never execute financial actions directly.
 */
@Entity
@Table(name = "promise_to_pay")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromiseToPay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recoveryCaseId;

    @Column(length = 2000)
    private String rawMessage;

    private String intent;

    private Double amount;

    private LocalDate promisedDate;

    private Boolean valid;

    private String validationReason;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

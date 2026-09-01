package com.recoverai.backend.dto.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskRequest {
    private Double amount;
    private Integer previousFailures;
    private Integer successfulPayments;
    private Double customerLtv;
    private String failureReason;
}

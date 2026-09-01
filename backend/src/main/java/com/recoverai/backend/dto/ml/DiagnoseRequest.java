package com.recoverai.backend.dto.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiagnoseRequest {
    private String entityType;
    private String failureReason;
    private String bank;
    private String paymentMethod;
    private String region;
    private String gateway;
}

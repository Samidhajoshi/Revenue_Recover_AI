package com.recoverai.backend.dto.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One aggregated (status, dimensions) row fed into ml-service's /diagnose - matches ml/schemas.py TransactionRecord. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionRecordDto {
    private String status;
    private String bank;
    private String paymentMethod;
    private String region;
    private String gateway;
    private Integer count;
}

package com.recoverai.backend.dto.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiagnoseRequest {
    // This case's own dimensions - unused by ml-service (extra fields are ignored),
    // kept only so MlClientService's local fallback can still describe this specific case.
    private String entityType;
    private String failureReason;
    private String bank;
    private String paymentMethod;
    private String region;
    private String gateway;

    // What ml-service's /diagnose actually requires: an aggregated (status, dimensions)
    // dataset it groups to find which segment fails above baseline.
    private List<TransactionRecordDto> transactions;
    private List<String> dimensions;
    @Builder.Default
    private Integer minSampleSize = 5;
    @Builder.Default
    private Integer topN = 5;
}

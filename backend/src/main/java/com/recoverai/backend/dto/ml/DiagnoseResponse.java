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
public class DiagnoseResponse {
    // Real fields returned by ml-service's /diagnose.
    private Integer totalTransactions;
    private Integer totalFailed;
    private Double overallFailureRate;
    private List<RootCauseSegmentDto> rootCauses;

    // Set only by MlClientService's local fallback (ml-service unreachable) - a
    // ready-to-display diagnosis string standing in for the rootCauses analysis.
    private String diagnosisNote;
}

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
public class GatewayAnomalyResponse {
    private Boolean anomalyDetected;
    private String severity;
    private Double baselineFailureRate;
    private Double currentFailureRate;
    private Double delta;
    private Double ratio;
    private List<String> rootCausePath;
    private List<RootCauseSegmentDto> rootCauses;
    // ml-service field is "recommended_action" - was "recommendation" before this fix, which
    // never matched and left this field silently null.
    private String recommendedAction;
}

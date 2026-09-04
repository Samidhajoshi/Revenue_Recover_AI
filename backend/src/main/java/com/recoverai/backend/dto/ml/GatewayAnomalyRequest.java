package com.recoverai.backend.dto.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayAnomalyRequest {
    // ml-service's schema requires "gateway", not "gateway_id" - it was never being sent
    // before this fix, which is why every gateway-case diagnosis was 422ing.
    private String gateway;
    private Double currentFailureRate;
    private Double baselineFailureRate;
}

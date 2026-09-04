package com.recoverai.backend.dto.ml;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** One ranked failure-rate-delta segment returned by ml-service - matches ml/schemas.py RootCauseSegment. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RootCauseSegmentDto {
    private Map<String, String> dimensions;
    private String label;
    private Integer sampleSize;
    private Integer failedCount;
    private Double segmentFailureRate;
    private Double overallFailureRate;
    private Double delta;
    private Double lift;
}

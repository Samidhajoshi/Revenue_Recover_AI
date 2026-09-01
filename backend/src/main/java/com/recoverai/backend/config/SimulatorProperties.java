package com.recoverai.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "recoverai.simulator")
@Data
public class SimulatorProperties {

    private long seed = 42L;

    /** failureReason -> (actionType -> successProbability) */
    private Map<String, Map<String, Double>> probabilities = new HashMap<>();

    private double gatewayRerouteSuccessRate = 0.80;
}

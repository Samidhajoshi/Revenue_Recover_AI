package com.recoverai.backend.service;

import com.recoverai.backend.config.SimulatorProperties;
import com.recoverai.backend.entity.enums.InterventionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Random;

/**
 * Deterministic-but-configurable simulator for what happens after an
 * intervention is executed (section 13). Uses a seeded Random so results are
 * reproducible; the seed and every probability come from
 * recoverai.simulator.* in application.yml, never hardcoded here.
 */
@Service
@RequiredArgsConstructor
public class RecoverySimulatorService {

    private final SimulatorProperties simulatorProperties;
    private Random random;

    private Random random() {
        if (random == null) {
            random = new Random(simulatorProperties.getSeed());
        }
        return random;
    }

    /** Re-seed the simulator (mainly for reproducible batch runs / tests). */
    public void reseed() {
        random = new Random(simulatorProperties.getSeed());
    }

    /**
     * Simulate the outcome of executing `action` against a case whose
     * failure reason is `failureReason`. Returns true if the intervention
     * recovered the payment.
     */
    public boolean simulateOutcome(String failureReason, InterventionType action) {
        double p = successProbability(failureReason, action);
        return random().nextDouble() < p;
    }

    public double successProbability(String failureReason, InterventionType action) {
        String reasonKey = failureReason == null ? "DEFAULT" : failureReason.toUpperCase();
        Map<String, Map<String, Double>> probabilities = simulatorProperties.getProbabilities();

        Map<String, Double> byAction = probabilities.getOrDefault(reasonKey, probabilities.get("DEFAULT"));
        if (byAction == null) {
            return 0.2;
        }
        Double p = byAction.get(action.name());
        if (p == null) {
            Map<String, Double> fallback = probabilities.get("DEFAULT");
            p = fallback == null ? 0.2 : fallback.getOrDefault(action.name(), 0.2);
        }
        return p;
    }

    public double gatewayRerouteSuccessRate() {
        return simulatorProperties.getGatewayRerouteSuccessRate();
    }

    public boolean simulateGatewayReroute() {
        return random().nextDouble() < gatewayRerouteSuccessRate();
    }
}

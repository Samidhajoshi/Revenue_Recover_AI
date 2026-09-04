package com.recoverai.backend.service;

import com.recoverai.backend.dto.ml.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Thin client for the external FastAPI ML service (section 9). Every call is
 * wrapped so that if the ML service is unreachable or errors out, the app
 * falls back to a simple in-Java heuristic and keeps running - the backend
 * must never hard-depend on the ML service being up.
 */
@Service
@Slf4j
public class MlClientService {

    private final WebClient webClient;
    private final boolean enabled;
    private final long timeoutMs;

    public MlClientService(WebClient mlServiceWebClient,
                            @Value("${recoverai.ml-service.enabled:true}") boolean enabled,
                            @Value("${recoverai.ml-service.timeout-ms:2000}") long timeoutMs) {
        this.webClient = mlServiceWebClient;
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
    }

    public RiskResponse risk(RiskRequest request) {
        if (enabled) {
            try {
                RiskResponse response = webClient.post()
                        .uri("/risk")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(RiskResponse.class)
                        .block(Duration.ofMillis(timeoutMs));
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                log.warn("ML /risk call failed, falling back to heuristic: {}", e.getMessage());
            }
        }
        return heuristicRisk(request);
    }

    public DiagnoseResponse diagnose(DiagnoseRequest request) {
        if (enabled) {
            try {
                DiagnoseResponse response = webClient.post()
                        .uri("/diagnose")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(DiagnoseResponse.class)
                        .block(Duration.ofMillis(timeoutMs));
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                log.warn("ML /diagnose call failed, falling back to heuristic: {}", e.getMessage());
            }
        }
        return heuristicDiagnose(request);
    }

    public GatewayAnomalyResponse gatewayAnomaly(GatewayAnomalyRequest request) {
        if (enabled) {
            try {
                GatewayAnomalyResponse response = webClient.post()
                        .uri("/gateway/anomaly")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(GatewayAnomalyResponse.class)
                        .block(Duration.ofMillis(timeoutMs));
                if (response != null) {
                    return response;
                }
            } catch (Exception e) {
                log.warn("ML /gateway/anomaly call failed, falling back to heuristic: {}", e.getMessage());
            }
        }
        return heuristicGatewayAnomaly(request);
    }

    // ---------------------------------------------------------------
    // Fallback heuristics - simple weighted scoring per section 9/10.
    // ---------------------------------------------------------------

    private RiskResponse heuristicRisk(RiskRequest r) {
        double amount = r.getAmount() == null ? 0 : r.getAmount();
        int prevFailures = r.getPreviousFailures() == null ? 0 : r.getPreviousFailures();
        int successfulPayments = r.getSuccessfulPayments() == null ? 0 : r.getSuccessfulPayments();
        double ltv = r.getCustomerLtv() == null ? 0 : r.getCustomerLtv();
        String reason = r.getFailureReason() == null ? "" : r.getFailureReason().toUpperCase();

        // weighted scoring: more prior failures + higher amount = higher risk;
        // more successful payments + higher LTV = lower risk.
        double score = 0.3
                + Math.min(0.35, prevFailures * 0.08)
                + Math.min(0.15, amount / 200000.0)
                - Math.min(0.25, successfulPayments * 0.02)
                - Math.min(0.15, ltv / 1000000.0);
        score = clamp(score, 0.02, 0.98);

        String tier;
        if (score >= 0.75) tier = "CRITICAL";
        else if (score >= 0.5) tier = "HIGH";
        else if (score >= 0.25) tier = "MEDIUM";
        else tier = "LOW";

        double recoveryProbability = switch (reason) {
            case "TEMPORARY_DECLINE" -> 0.65;
            case "INSUFFICIENT_FUNDS" -> 0.30;
            case "EXPIRED_CARD" -> 0.50;
            case "GATEWAY_FAILURE" -> 0.55;
            case "DISPUTED" -> 0.05;
            case "ABANDONED_CHECKOUT" -> 0.25;
            default -> 0.35;
        };
        recoveryProbability = clamp(recoveryProbability - (prevFailures * 0.05), 0.02, 0.95);

        return RiskResponse.builder()
                .riskScore(round(score))
                .riskTier(tier)
                .recoveryProbability(round(recoveryProbability))
                .build();
    }

    // The caller (AgentOrchestratorService) now sends only the dataset-wide
    // transactions aggregate here - no per-case fields - since the ranked root
    // causes are identical for every case in a run. There's nothing case-specific
    // left to build a detailed fallback from; the caller prefixes this note with
    // the case's own failure reason.
    private DiagnoseResponse heuristicDiagnose(DiagnoseRequest r) {
        return DiagnoseResponse.builder()
                .diagnosisNote("root-cause analysis unavailable (ml-service unreachable)")
                .build();
    }

    private GatewayAnomalyResponse heuristicGatewayAnomaly(GatewayAnomalyRequest r) {
        double current = r.getCurrentFailureRate() == null ? 0 : r.getCurrentFailureRate();
        double baseline = r.getBaselineFailureRate() == null ? 0 : r.getBaselineFailureRate();
        // Failure rates are 0-1 fractions (see CsvImportService#asFraction); thresholds below are in the
        // same scale (0.02 = a 2-percentage-point jump).
        double delta = current - baseline;
        boolean anomalous = baseline > 0 ? (current > baseline * 1.5 && delta > 0.02) : current > 0.10;
        String severity;
        if (delta > 0.15) severity = "CRITICAL";
        else if (delta > 0.08) severity = "HIGH";
        else if (delta > 0.03) severity = "MEDIUM";
        else severity = "LOW";
        String recommendedAction = anomalous
                ? "Reroute affected traffic away from " + r.getGateway() + " to a healthy alternate gateway."
                : "No action required; failure rate within normal bounds.";
        return GatewayAnomalyResponse.builder()
                .anomalyDetected(anomalous)
                .severity(severity)
                .baselineFailureRate(baseline)
                .currentFailureRate(current)
                .delta(delta)
                .recommendedAction(recommendedAction)
                .build();
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

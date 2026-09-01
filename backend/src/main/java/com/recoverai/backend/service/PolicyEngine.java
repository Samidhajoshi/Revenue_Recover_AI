package com.recoverai.backend.service;

import com.recoverai.backend.config.PolicyProperties;
import com.recoverai.backend.dto.PolicyContext;
import com.recoverai.backend.dto.PolicyDecision;
import com.recoverai.backend.entity.enums.InterventionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Implements the example policy rules from section 8 of the implementation
 * plan, literally:
 *
 *  TEMPORARY_DECLINE AND retry_count<3 AND opted_out=false -> RETRY
 *  EXPIRED_CARD -> SEND_PAYMENT_UPDATE
 *  retry_count>=3 -> STOP/ESCALATE
 *  DISPUTED=true -> HUMAN_ESCALATION
 *  payment_status=SUCCESS -> STOP ALL
 *  outside quiet hours -> DO NOT SEND MESSAGE
 *
 * All thresholds are sourced from PolicyProperties (recoverai.policy.* in
 * application.yml) rather than hardcoded.
 */
@Service
@RequiredArgsConstructor
public class PolicyEngine {

    private final PolicyProperties policyProperties;

    public PolicyDecision evaluate(PolicyContext ctx) {
        String failureReason = ctx.getFailureReason() == null ? "" : ctx.getFailureReason().toUpperCase();
        int retryCount = ctx.getRetryCount() == null ? 0 : ctx.getRetryCount();
        boolean optedOut = Boolean.TRUE.equals(ctx.getOptedOut());
        boolean disputed = Boolean.TRUE.equals(ctx.getDisputed());
        boolean alreadySucceeded = Boolean.TRUE.equals(ctx.getAlreadySucceeded());

        // payment_status = SUCCESS -> STOP ALL RECOVERY ACTIONS
        if (alreadySucceeded) {
            return PolicyDecision.builder()
                    .allowed(false).stop(true).escalate(false)
                    .reasonCode("ALREADY_SUCCEEDED")
                    .reason("Payment already succeeded; stopping all recovery actions.")
                    .build();
        }

        // DISPUTED = true -> HUMAN_ESCALATION. Automated recovery actions are blocked outright;
        // ESCALATE/STOP themselves remain evaluable (a disputed case must still be able to select
        // ESCALATE as its counterfactual winner, not just fall back to it when everything is blocked).
        boolean isSafetyValve = ctx.getProposedAction() == InterventionType.ESCALATE
                || ctx.getProposedAction() == InterventionType.STOP;
        if (disputed && !isSafetyValve) {
            return PolicyDecision.builder()
                    .allowed(false).stop(false).escalate(true)
                    .reasonCode("DISPUTED")
                    .reason("Transaction is disputed; automated recovery actions are blocked, only escalation is permitted.")
                    .build();
        }

        // retry_count >= maxRetries -> STOP / ESCALATE
        if (retryCount >= policyProperties.getMaxRetries()) {
            boolean highValue = ctx.getAmountAtRisk() != null
                    && ctx.getAmountAtRisk() >= policyProperties.getHighValueThreshold();
            return PolicyDecision.builder()
                    .allowed(false)
                    .stop(!highValue)
                    .escalate(highValue)
                    .reasonCode("MAX_RETRIES_EXCEEDED")
                    .reason("retry_count (" + retryCount + ") >= maxRetries (" + policyProperties.getMaxRetries()
                            + "); " + (highValue ? "escalating high-value case" : "stopping recovery"))
                    .build();
        }

        // opted_out customers may never receive outreach
        if (optedOut && isCommunicationAction(ctx.getProposedAction())) {
            return PolicyDecision.builder()
                    .allowed(false).stop(true).escalate(false)
                    .reasonCode("OPTED_OUT")
                    .reason("Customer has opted out of communications.")
                    .build();
        }

        // minimum recovery probability guard - never blocks ESCALATE/STOP themselves, since
        // they're the safety valve a low-probability case is supposed to fall back to.
        if (ctx.getRecoveryProbability() != null
                && ctx.getRecoveryProbability() < policyProperties.getMinimumRecoveryProbability()
                && !EXPIRED_CARD.equals(failureReason)
                && !isSafetyValve) {
            return PolicyDecision.builder()
                    .allowed(false).stop(false).escalate(true)
                    .reasonCode("BELOW_MIN_RECOVERY_PROBABILITY")
                    .reason("Recovery probability " + ctx.getRecoveryProbability()
                            + " is below configured minimum " + policyProperties.getMinimumRecoveryProbability())
                    .build();
        }

        InterventionType enforced = ctx.getProposedAction();

        // EXPIRED_CARD -> SEND_PAYMENT_UPDATE (never a blind retry)
        if (EXPIRED_CARD.equals(failureReason) && enforced == InterventionType.RETRY_PAYMENT) {
            enforced = InterventionType.PAYMENT_LINK;
        }

        // TEMPORARY_DECLINE AND retry_count<maxRetries AND !optedOut -> RETRY is allowed as-is (default path)

        // quiet hours -> DO NOT SEND MESSAGE (only affects messaging-style actions; deferred not stopped)
        if (isCommunicationAction(enforced) && isOutsideQuietHours(ctx.getCurrentTime())) {
            return PolicyDecision.builder()
                    .allowed(false).stop(false).escalate(false).deferred(true)
                    .enforcedAction(enforced)
                    .reasonCode("QUIET_HOURS")
                    .reason("Current time is within configured quiet hours ("
                            + policyProperties.getQuietHours().getStart() + " - "
                            + policyProperties.getQuietHours().getEnd() + "); message deferred.")
                    .build();
        }

        return PolicyDecision.builder()
                .allowed(true).stop(false).escalate(false)
                .enforcedAction(enforced)
                .reasonCode("APPROVED")
                .reason("Action approved by policy engine.")
                .build();
    }

    private static final String EXPIRED_CARD = "EXPIRED_CARD";

    private boolean isCommunicationAction(InterventionType type) {
        return type == InterventionType.SEND_MESSAGE || type == InterventionType.PAYMENT_LINK;
    }

    /**
     * Quiet hours window (e.g. 20:00 -> 09:00) wraps past midnight. "Outside
     * allowed communication hours" per the plan means inside this quiet window.
     */
    public boolean isOutsideQuietHours(java.time.LocalDateTime currentTime) {
        if (currentTime == null) {
            return false;
        }
        LocalTime time = currentTime.toLocalTime();
        LocalTime start = LocalTime.parse(policyProperties.getQuietHours().getStart(), DateTimeFormatter.ofPattern("HH:mm"));
        LocalTime end = LocalTime.parse(policyProperties.getQuietHours().getEnd(), DateTimeFormatter.ofPattern("HH:mm"));

        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        } else {
            // wraps midnight, e.g. 20:00 -> 09:00
            return !time.isBefore(start) || time.isBefore(end);
        }
    }
}

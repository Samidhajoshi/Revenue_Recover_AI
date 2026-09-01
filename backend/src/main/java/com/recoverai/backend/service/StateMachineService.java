package com.recoverai.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverai.backend.entity.AuditLog;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.enums.RecoveryState;
import com.recoverai.backend.repository.AuditLogRepository;
import com.recoverai.backend.repository.RecoveryCaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Owns every RecoveryCase.currentState transition. No other component may
 * mutate currentState directly - this keeps the state machine the single
 * source of truth for "what happens next" and guarantees every transition
 * is captured in the audit trail (section 7 / 19 of the implementation plan).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StateMachineService {

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<RecoveryState, Set<RecoveryState>> TRANSITIONS = new EnumMap<>(RecoveryState.class);

    static {
        TRANSITIONS.put(RecoveryState.DETECTED, EnumSet.of(RecoveryState.DIAGNOSING, RecoveryState.STOPPED));
        TRANSITIONS.put(RecoveryState.DIAGNOSING, EnumSet.of(RecoveryState.DIAGNOSED, RecoveryState.STOPPED));
        TRANSITIONS.put(RecoveryState.DIAGNOSED, EnumSet.of(RecoveryState.DECIDING, RecoveryState.ESCALATED, RecoveryState.STOPPED));
        TRANSITIONS.put(RecoveryState.DECIDING, EnumSet.of(RecoveryState.POLICY_CHECK, RecoveryState.ESCALATED, RecoveryState.STOPPED));
        TRANSITIONS.put(RecoveryState.POLICY_CHECK, EnumSet.of(RecoveryState.INTERVENING, RecoveryState.ESCALATED,
                RecoveryState.STOPPED, RecoveryState.WAITING));
        TRANSITIONS.put(RecoveryState.INTERVENING, EnumSet.of(RecoveryState.WAITING, RecoveryState.STOPPED));
        TRANSITIONS.put(RecoveryState.WAITING, EnumSet.of(
                RecoveryState.RECOVERED,
                RecoveryState.NEXT_INTERVENTION,
                RecoveryState.ESCALATED,
                RecoveryState.STOPPED,
                // a case deferred in WAITING due to quiet hours resumes here once the
                // simulated clock advances past the quiet window (see advance-time API)
                RecoveryState.DECIDING));
        TRANSITIONS.put(RecoveryState.NEXT_INTERVENTION, EnumSet.of(RecoveryState.DECIDING, RecoveryState.STOPPED));
        // terminal states
        TRANSITIONS.put(RecoveryState.RECOVERED, EnumSet.noneOf(RecoveryState.class));
        TRANSITIONS.put(RecoveryState.ESCALATED, EnumSet.noneOf(RecoveryState.class));
        TRANSITIONS.put(RecoveryState.STOPPED, EnumSet.noneOf(RecoveryState.class));
    }

    public boolean isTerminal(RecoveryState state) {
        return TRANSITIONS.getOrDefault(state, EnumSet.noneOf(RecoveryState.class)).isEmpty();
    }

    public boolean canTransition(RecoveryState from, RecoveryState to) {
        return TRANSITIONS.getOrDefault(from, EnumSet.noneOf(RecoveryState.class)).contains(to);
    }

    /**
     * Transition a case to a new state, persist it, and write the audit log
     * entry. Throws IllegalStateException if the transition is not allowed.
     */
    public RecoveryCase transition(RecoveryCase recoveryCase, RecoveryState newState, String eventType,
                                    String actor, String reason, Object metadata) {
        RecoveryState previous = recoveryCase.getCurrentState();
        if (!canTransition(previous, newState)) {
            throw new IllegalStateException("Illegal state transition: " + previous + " -> " + newState
                    + " for case " + recoveryCase.getId());
        }
        recoveryCase.setCurrentState(newState);
        RecoveryCase saved = recoveryCaseRepository.save(recoveryCase);

        String metadataJson;
        try {
            metadataJson = metadata == null ? null : objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            metadataJson = String.valueOf(metadata);
        }

        AuditLog log = AuditLog.builder()
                .recoveryCaseId(saved.getId())
                .eventType(eventType)
                .previousState(previous == null ? null : previous.name())
                .newState(newState.name())
                .actor(actor)
                .reason(reason)
                .metadata(metadataJson)
                .build();
        auditLogRepository.save(log);

        return saved;
    }

    /** Log a non-state-changing audit event (e.g. POLICY_APPROVED, ACTION_EXECUTED) tied to a case. */
    public void logEvent(RecoveryCase recoveryCase, String eventType, String actor, String reason, Object metadata) {
        String metadataJson;
        try {
            metadataJson = metadata == null ? null : objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            metadataJson = String.valueOf(metadata);
        }
        AuditLog log = AuditLog.builder()
                .recoveryCaseId(recoveryCase.getId())
                .eventType(eventType)
                .previousState(recoveryCase.getCurrentState() == null ? null : recoveryCase.getCurrentState().name())
                .newState(recoveryCase.getCurrentState() == null ? null : recoveryCase.getCurrentState().name())
                .actor(actor)
                .reason(reason)
                .metadata(metadataJson)
                .build();
        auditLogRepository.save(log);
    }
}

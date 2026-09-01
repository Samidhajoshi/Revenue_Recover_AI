package com.recoverai.backend.dto;

import com.recoverai.backend.entity.enums.InterventionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of the PolicyEngine evaluating a proposed action against the
 * configured business rules.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyDecision {

    /** true if the proposed action may be executed as-is */
    private boolean allowed;

    /** true if the case must be escalated to a human instead of acting automatically */
    private boolean escalate;

    /** true if all recovery activity on the case must stop */
    private boolean stop;

    /** true if this decision merely defers the action (e.g. quiet hours) rather than stopping the case */
    private boolean deferred;

    /** the action that is actually allowed to run (may differ from what was proposed, e.g. EXPIRED_CARD forces SEND_MESSAGE/PAYMENT_LINK) */
    private InterventionType enforcedAction;

    private String reasonCode;

    private String reason;
}

package com.recoverai.backend.entity.enums;

public enum RecoveryState {
    DETECTED,
    DIAGNOSING,
    DIAGNOSED,
    DECIDING,
    POLICY_CHECK,
    INTERVENING,
    WAITING,
    RECOVERED,
    NEXT_INTERVENTION,
    ESCALATED,
    STOPPED
}

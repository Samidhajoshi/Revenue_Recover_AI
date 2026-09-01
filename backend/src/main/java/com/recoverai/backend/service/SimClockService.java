package com.recoverai.backend.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * A simple simulated clock. Defaults to wall-clock time but can be advanced
 * via POST /api/simulation/advance-time so that WAITING cases can progress
 * and quiet-hours logic can be exercised deterministically in a demo.
 */
@Service
public class SimClockService {

    private LocalDateTime simulatedNow = LocalDateTime.now();

    public LocalDateTime now() {
        return simulatedNow;
    }

    public LocalDateTime advanceMinutes(long minutes) {
        simulatedNow = simulatedNow.plus(minutes, ChronoUnit.MINUTES);
        return simulatedNow;
    }

    public LocalDateTime reset() {
        simulatedNow = LocalDateTime.now();
        return simulatedNow;
    }
}

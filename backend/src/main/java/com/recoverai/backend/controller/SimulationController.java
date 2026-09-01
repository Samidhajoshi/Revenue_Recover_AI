package com.recoverai.backend.controller;

import com.recoverai.backend.dto.CaseRunResult;
import com.recoverai.backend.dto.SimulationRunResult;
import com.recoverai.backend.service.AgentOrchestratorService;
import com.recoverai.backend.service.SimClockService;
import com.recoverai.backend.service.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationService simulationService;
    private final SimClockService simClockService;
    private final AgentOrchestratorService orchestratorService;

    @PostMapping("/run")
    public SimulationRunResult run() {
        return simulationService.run();
    }

    /** Advances the simulated clock so WAITING cases can progress / quiet-hours logic can be exercised. */
    @PostMapping("/advance-time")
    public Map<String, Object> advanceTime(@RequestParam(defaultValue = "60") long minutes) {
        LocalDateTime now = simClockService.advanceMinutes(minutes);
        List<CaseRunResult> resumed = orchestratorService.resumeWaitingCases();
        return Map.of("simulatedNow", now.toString(), "advancedMinutes", minutes, "resumedCases", resumed);
    }
}

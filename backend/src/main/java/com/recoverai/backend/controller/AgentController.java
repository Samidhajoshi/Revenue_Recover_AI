package com.recoverai.backend.controller;

import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.service.AgentOrchestratorService;
import com.recoverai.backend.service.DetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentOrchestratorService orchestratorService;
    private final DetectionService detectionService;

    /**
     * Detection only (Phase 2) - scans imported data and creates DETECTED
     * cases, but never runs the agent loop. Lets the dashboard show real
     * "revenue at risk" / "active cases" numbers for a freshly-imported
     * batch before anyone clicks run.
     */
    @PostMapping("/detect")
    public Map<String, Integer> detect() {
        return Map.of("casesCreated", detectionService.detectAll());
    }

    /** Risk + diagnosis only, no execution. */
    @PostMapping("/analyze/{id}")
    public RecoveryCase analyze(@PathVariable Long id) {
        return orchestratorService.analyze(id);
    }

    /** Execute the decided action for this case (one decide->policy->execute->observe cycle). */
    @PostMapping("/execute/{id}")
    public RecoveryCase execute(@PathVariable Long id) {
        return orchestratorService.executeDecided(id);
    }

    /** Run every DETECTED case through the full agent loop. */
    @PostMapping("/run-batch")
    public List<?> runBatch() {
        detectionService.detectAll();
        return orchestratorService.allDetectedCases().stream()
                .map(RecoveryCase::getId)
                .map(orchestratorService::runCase)
                .collect(Collectors.toList());
    }
}

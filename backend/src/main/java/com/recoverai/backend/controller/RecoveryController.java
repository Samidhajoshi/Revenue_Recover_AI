package com.recoverai.backend.controller;

import com.recoverai.backend.dto.CaseRunResult;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.entity.enums.RecoveryState;
import com.recoverai.backend.entity.enums.RiskTier;
import com.recoverai.backend.repository.RecoveryCaseRepository;
import com.recoverai.backend.service.AgentOrchestratorService;
import com.recoverai.backend.service.DetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/recovery")
@RequiredArgsConstructor
public class RecoveryController {

    private final RecoveryCaseRepository recoveryCaseRepository;
    private final AgentOrchestratorService orchestratorService;
    private final DetectionService detectionService;

    @GetMapping
    public List<RecoveryCase> list(@RequestParam(required = false) String type,
                                    @RequestParam(required = false) String riskTier,
                                    @RequestParam(required = false) String status) {
        List<RecoveryCase> cases = recoveryCaseRepository.findAll();

        if (type != null && !type.isBlank()) {
            EntityType et = parseEnumSafe(type.toUpperCase(), EntityType.class);
            if (et != null) {
                cases = cases.stream().filter(c -> c.getEntityType() == et).collect(Collectors.toList());
            }
        }
        if (riskTier != null && !riskTier.isBlank()) {
            RiskTier rt = parseEnumSafe(riskTier.toUpperCase(), RiskTier.class);
            if (rt != null) {
                cases = cases.stream().filter(c -> c.getRiskTier() == rt).collect(Collectors.toList());
            }
        }
        if (status != null && !status.isBlank()) {
            RecoveryState st = parseEnumSafe(status.toUpperCase(), RecoveryState.class);
            if (st != null) {
                cases = cases.stream().filter(c -> c.getCurrentState() == st).collect(Collectors.toList());
            }
        }
        return cases;
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecoveryCase> get(@PathVariable Long id) {
        return recoveryCaseRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<CaseRunResult> runOne(@PathVariable Long id) {
        return ResponseEntity.ok(orchestratorService.runCase(id));
    }

    @PostMapping("/run-batch")
    public List<CaseRunResult> runBatch() {
        detectionService.detectAll();
        List<Long> ids = orchestratorService.allDetectedCases().stream()
                .map(RecoveryCase::getId).collect(Collectors.toList());
        return ids.stream().map(orchestratorService::runCase).collect(Collectors.toList());
    }

    private <T extends Enum<T>> T parseEnumSafe(String value, Class<T> type) {
        try {
            return Enum.valueOf(type, value);
        } catch (Exception e) {
            return null;
        }
    }
}

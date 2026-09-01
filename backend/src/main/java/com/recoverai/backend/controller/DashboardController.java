package com.recoverai.backend.controller;

import com.recoverai.backend.dto.DashboardSummary;
import com.recoverai.backend.dto.RecoveryByTypeRow;
import com.recoverai.backend.dto.TimelinePoint;
import com.recoverai.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary();
    }

    @GetMapping("/recovery-by-type")
    public List<RecoveryByTypeRow> recoveryByType() {
        return dashboardService.recoveryByType();
    }

    @GetMapping("/timeline")
    public List<TimelinePoint> timeline() {
        return dashboardService.timeline();
    }
}

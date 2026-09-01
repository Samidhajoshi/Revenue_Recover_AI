package com.recoverai.backend.service;

import com.recoverai.backend.dto.DashboardSummary;
import com.recoverai.backend.dto.RecoveryByTypeRow;
import com.recoverai.backend.dto.TimelinePoint;
import com.recoverai.backend.entity.RecoveryCase;
import com.recoverai.backend.entity.enums.EntityType;
import com.recoverai.backend.entity.enums.RecoveryState;
import com.recoverai.backend.repository.RecoveryCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final RecoveryCaseRepository recoveryCaseRepository;

    private static final Set<RecoveryState> TERMINAL = EnumSet.of(
            RecoveryState.RECOVERED, RecoveryState.STOPPED, RecoveryState.ESCALATED);

    public DashboardSummary summary() {
        List<RecoveryCase> all = recoveryCaseRepository.findAll();

        double revenueAtRisk = all.stream()
                .filter(c -> !TERMINAL.contains(c.getCurrentState()))
                .mapToDouble(c -> nz(c.getAmountAtRisk()))
                .sum();

        double recoverableRevenue = all.stream()
                .filter(c -> !TERMINAL.contains(c.getCurrentState()))
                .mapToDouble(c -> nz(c.getAmountAtRisk()) * (c.getRecoveryProbability() == null ? 0.3 : c.getRecoveryProbability()))
                .sum();

        double recoveredRevenue = all.stream()
                .mapToDouble(c -> nz(c.getRecoveredAmount()))
                .sum();

        double totalAmount = all.stream().mapToDouble(c -> nz(c.getAmountAtRisk())).sum();
        double recoveryRate = totalAmount > 0 ? recoveredRevenue / totalAmount : 0.0;

        long activeCases = all.stream().filter(c -> !TERMINAL.contains(c.getCurrentState())).count();
        long escalations = all.stream().filter(c -> c.getCurrentState() == RecoveryState.ESCALATED).count();
        long stopped = all.stream().filter(c -> c.getCurrentState() == RecoveryState.STOPPED).count();

        return DashboardSummary.builder()
                .revenueAtRisk(round(revenueAtRisk))
                .recoverableRevenue(round(recoverableRevenue))
                .recoveredRevenue(round(recoveredRevenue))
                .recoveryRate(round(recoveryRate))
                .activeCases(activeCases)
                .escalations(escalations)
                .totalCases((long) all.size())
                .stoppedCases(stopped)
                .build();
    }

    public List<RecoveryByTypeRow> recoveryByType() {
        List<RecoveryCase> all = recoveryCaseRepository.findAll();
        Map<EntityType, List<RecoveryCase>> byType = all.stream()
                .collect(Collectors.groupingBy(RecoveryCase::getEntityType));

        List<RecoveryByTypeRow> rows = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            List<RecoveryCase> cases = byType.getOrDefault(type, List.of());
            double amountAtRisk = cases.stream().mapToDouble(c -> nz(c.getAmountAtRisk())).sum();
            double recovered = cases.stream().mapToDouble(c -> nz(c.getRecoveredAmount())).sum();
            double rate = amountAtRisk > 0 ? recovered / amountAtRisk : 0.0;
            rows.add(RecoveryByTypeRow.builder()
                    .entityType(type.name())
                    .casesCount((long) cases.size())
                    .amountAtRisk(round(amountAtRisk))
                    .recoveredAmount(round(recovered))
                    .recoveryRate(round(rate))
                    .build());
        }
        return rows;
    }

    public List<TimelinePoint> timeline() {
        List<RecoveryCase> recovered = recoveryCaseRepository.findByCurrentState(RecoveryState.RECOVERED);
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;

        Map<String, List<RecoveryCase>> byDate = recovered.stream()
                .filter(c -> c.getUpdatedAt() != null)
                .collect(Collectors.groupingBy(c -> c.getUpdatedAt().toLocalDate().format(fmt)));

        return byDate.entrySet().stream()
                .map(e -> TimelinePoint.builder()
                        .date(e.getKey())
                        .recoveredAmount(round(e.getValue().stream().mapToDouble(c -> nz(c.getRecoveredAmount())).sum()))
                        .recoveredCases((long) e.getValue().size())
                        .build())
                .sorted(Comparator.comparing(TimelinePoint::getDate))
                .collect(Collectors.toList());
    }

    private double nz(Double d) {
        return d == null ? 0.0 : d;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

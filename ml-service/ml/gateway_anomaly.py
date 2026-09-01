"""
Gateway anomaly detection for POST /gateway/anomaly.

Per section 5 of the plan (Scenario C — Payment Gateway Degradation) and
section 10 (root-cause diagnosis), this compares a gateway's current
failure rate against its own baseline, flags an anomaly with a severity
tier, and — when transaction-level detail is supplied — reuses the same
ranked-failure-rate-delta slicing from diagnosis.py to drill down:

    Gateway -> Payment method -> Bank -> Region

producing a root_cause_path like:
    ["Gateway A", "UPI", "Bank C", "North region"]

--------------------------------------------------------------------------
ANOMALY DETECTION
--------------------------------------------------------------------------
delta = current_failure_rate - baseline_failure_rate
ratio = current_failure_rate / baseline_failure_rate  (guarded against 0)

anomaly_detected = True if delta >= 0.03 (3 absolute percentage points)
                    OR ratio >= 2.0 (rate has at least doubled)

Severity thresholds (first match wins, most severe first):
    CRITICAL : delta >= 0.15 or ratio >= 5
    HIGH     : delta >= 0.08 or ratio >= 3
    MEDIUM   : delta >= 0.03 or ratio >= 2
    LOW      : delta >  0    (small uptick, not yet flagged as anomaly)
    NONE     : delta <= 0    (current rate at or below baseline)

These thresholds are simple, documented constants (not a trained model),
consistent with "Do not over-engineer the ML" in section 9.
"""

from __future__ import annotations

from typing import List, Optional

from ml.diagnosis import diagnose
from ml.schemas import GatewayAnomalyRequest, RootCauseSegment, TransactionRecord

DEFAULT_DRILLDOWN_DIMENSIONS = ["payment_method", "bank", "region"]

_ANOMALY_DELTA_THRESHOLD = 0.03
_ANOMALY_RATIO_THRESHOLD = 2.0


def _severity(delta: float, ratio: float) -> str:
    if delta <= 0:
        return "NONE"
    if delta >= 0.15 or ratio >= 5:
        return "CRITICAL"
    if delta >= 0.08 or ratio >= 3:
        return "HIGH"
    if delta >= _ANOMALY_DELTA_THRESHOLD or ratio >= _ANOMALY_RATIO_THRESHOLD:
        return "MEDIUM"
    return "LOW"


def _build_root_cause_path(gateway: str, top_segment: Optional[RootCauseSegment], dims_order: List[str]) -> List[str]:
    path = [gateway]
    if top_segment is None:
        return path
    # Walk dims_order so the path reads gateway -> payment_method -> bank -> region,
    # regardless of the order dict keys came back from groupby.
    for dim in dims_order:
        if dim in top_segment.dimensions:
            value = top_segment.dimensions[dim]
            path.append(f"{value} region" if dim == "region" else value)
    return path


def detect_anomaly(request: GatewayAnomalyRequest):
    baseline = request.baseline_failure_rate
    current = request.current_failure_rate

    delta = current - baseline
    ratio = (current / baseline) if baseline > 0 else (float("inf") if current > 0 else 1.0)

    anomaly_detected = delta >= _ANOMALY_DELTA_THRESHOLD or ratio >= _ANOMALY_RATIO_THRESHOLD
    severity = _severity(delta, ratio)

    root_causes: List[RootCauseSegment] = []
    root_cause_path: List[str] = [request.gateway]

    dims_order = request.dimensions or DEFAULT_DRILLDOWN_DIMENSIONS

    if anomaly_detected and request.transactions:
        _, _, _, root_causes = diagnose(
            transactions=request.transactions,
            dimensions=dims_order,
            min_sample_size=request.min_sample_size,
            top_n=5,
        )
        top_segment = root_causes[0] if root_causes else None
        root_cause_path = _build_root_cause_path(request.gateway, top_segment, dims_order)

    if not anomaly_detected:
        recommended_action = "NONE"
    elif severity in ("HIGH", "CRITICAL"):
        recommended_action = "REROUTE_TO_ALTERNATE_GATEWAY"
    else:
        recommended_action = "MONITOR"

    return {
        "gateway": request.gateway,
        "anomaly_detected": anomaly_detected,
        "severity": severity,
        "baseline_failure_rate": round(baseline, 4),
        "current_failure_rate": round(current, 4),
        "delta": round(delta, 4),
        "ratio": round(ratio, 4) if ratio != float("inf") else ratio,
        "root_cause_path": root_cause_path,
        "root_causes": root_causes,
        "recommended_action": recommended_action,
    }

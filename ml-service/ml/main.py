"""
RecoverAI FastAPI ML Service — entrypoint.

Implements section 9 of docs/IMPLEMENTATION_PLAN.txt:
    POST /risk               - weighted risk scoring (risk.py)
    POST /diagnose            - ranked failure-rate-delta root cause (diagnosis.py)
    POST /gateway/anomaly    - gateway anomaly detection + drill-down (gateway_anomaly.py)
    GET  /health

Run from the ml-service/ directory with:
    uvicorn ml.main:app --reload
"""

from fastapi import FastAPI

from ml.diagnosis import diagnose
from ml.gateway_anomaly import detect_anomaly
from ml.risk import score_risk
from ml.schemas import (
    DiagnoseRequest,
    DiagnoseResponse,
    GatewayAnomalyRequest,
    GatewayAnomalyResponse,
    HealthResponse,
    RiskRequest,
    RiskResponse,
)

app = FastAPI(
    title="RecoverAI ML Service",
    description=(
        "Risk scoring, root-cause diagnosis, and gateway anomaly detection "
        "for the RecoverAI autonomous revenue recovery controller. All "
        "endpoints use transparent weighted scoring / statistical group-by "
        "slicing, per section 9 of the implementation plan — no trained "
        "models required for v1."
    ),
    version="0.1.0",
)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse()


@app.post("/risk", response_model=RiskResponse)
def risk(request: RiskRequest) -> RiskResponse:
    return score_risk(request)


@app.post("/diagnose", response_model=DiagnoseResponse)
def diagnose_endpoint(request: DiagnoseRequest) -> DiagnoseResponse:
    total_transactions, total_failed, overall_failure_rate, root_causes = diagnose(
        transactions=request.transactions,
        dimensions=request.dimensions,
        min_sample_size=request.min_sample_size,
        top_n=request.top_n,
    )
    return DiagnoseResponse(
        total_transactions=total_transactions,
        total_failed=total_failed,
        overall_failure_rate=overall_failure_rate,
        root_causes=root_causes,
    )


@app.post("/gateway/anomaly", response_model=GatewayAnomalyResponse)
def gateway_anomaly_endpoint(request: GatewayAnomalyRequest) -> GatewayAnomalyResponse:
    result = detect_anomaly(request)
    return GatewayAnomalyResponse(**result)

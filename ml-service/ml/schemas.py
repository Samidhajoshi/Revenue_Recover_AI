"""
Pydantic request/response models for the RecoverAI FastAPI ML Service.

Field names follow the DATABASE DESIGN section of docs/IMPLEMENTATION_PLAN.txt
(section 6) wherever the plan defines an equivalent column, so payloads coming
straight out of Spring Boot / MySQL (transactions, gateways, customers) map
onto these models with no renaming.
"""

from typing import Dict, List, Optional

from pydantic import BaseModel, Field

# --------------------------------------------------------------------------
# /risk
# --------------------------------------------------------------------------


class RiskRequest(BaseModel):
    amount: float = Field(..., gt=0, description="Transaction / subscription amount at risk")
    previous_failures: int = Field(0, ge=0, description="Prior failed attempts for this customer/entity")
    successful_payments: int = Field(0, ge=0, description="Customer's historical successful payments")
    customer_ltv: float = Field(0, ge=0, description="Customer lifetime value")
    failure_reason: str = Field(
        ...,
        description=(
            "One of: temporary_decline, insufficient_funds, expired_card, "
            "gateway_failure, abandoned_checkout, disputed (case-insensitive; "
            "unrecognized values fall back to a neutral default)"
        ),
    )

    class Config:
        json_schema_extra = {
            "example": {
                "amount": 8500,
                "previous_failures": 1,
                "successful_payments": 12,
                "customer_ltv": 45000,
                "failure_reason": "temporary_decline",
            }
        }


class RiskResponse(BaseModel):
    risk_score: float = Field(..., ge=0, le=1, description="0 (safe) - 1 (high risk of permanent revenue loss)")
    risk_tier: str = Field(..., description="LOW | MEDIUM | HIGH")
    recovery_probability: float = Field(
        ..., ge=0, le=1, description="Probability of recovering the amount using the best-suited action"
    )
    recommended_action: str = Field(
        ..., description="Best-suited action this probability is based on, e.g. RETRY_PAYMENT / SEND_PAYMENT_LINK"
    )


# --------------------------------------------------------------------------
# /diagnose
# --------------------------------------------------------------------------


class TransactionRecord(BaseModel):
    """One transaction (or one pre-aggregated row) fed into root-cause diagnosis."""

    status: str = Field(..., description="SUCCESS or FAILED (case-insensitive)")
    bank: Optional[str] = None
    payment_method: Optional[str] = None
    region: Optional[str] = None
    gateway: Optional[str] = None
    failure_reason: Optional[str] = None
    created_at: Optional[str] = Field(None, description="ISO timestamp, currently informational only")
    count: int = Field(1, ge=1, description="Weight of this row if it represents an aggregated count")


class DiagnoseRequest(BaseModel):
    transactions: List[TransactionRecord] = Field(..., min_length=1)
    dimensions: Optional[List[str]] = Field(
        None,
        description=(
            "Which fields to slice on. Defaults to "
            "['bank', 'payment_method', 'region', 'gateway']. "
            "Combinations up to 3 dimensions deep are evaluated."
        ),
    )
    min_sample_size: int = Field(5, ge=1, description="Segments with fewer transactions than this are ignored")
    top_n: int = Field(5, ge=1, le=50, description="How many ranked root causes to return")

    class Config:
        json_schema_extra = {
            "example": {
                "transactions": [
                    {"status": "FAILED", "bank": "Bank C", "payment_method": "UPI", "region": "North", "gateway": "Gateway A", "count": 40},
                    {"status": "SUCCESS", "bank": "Bank C", "payment_method": "UPI", "region": "North", "gateway": "Gateway A", "count": 60},
                    {"status": "FAILED", "bank": "Bank A", "payment_method": "UPI", "region": "North", "gateway": "Gateway A", "count": 3},
                    {"status": "SUCCESS", "bank": "Bank A", "payment_method": "UPI", "region": "North", "gateway": "Gateway A", "count": 97},
                ]
            }
        }


class RootCauseSegment(BaseModel):
    dimensions: Dict[str, str] = Field(..., description="e.g. {'bank': 'Bank C', 'payment_method': 'UPI', 'region': 'North'}")
    label: str = Field(..., description="Human-readable form, e.g. 'Bank C + UPI + North region'")
    sample_size: int
    failed_count: int
    segment_failure_rate: float
    overall_failure_rate: float
    delta: float = Field(..., description="segment_failure_rate - overall_failure_rate")
    lift: float = Field(..., description="segment_failure_rate / overall_failure_rate (guarded against div-by-zero)")


class DiagnoseResponse(BaseModel):
    total_transactions: int
    total_failed: int
    overall_failure_rate: float
    root_causes: List[RootCauseSegment]


# --------------------------------------------------------------------------
# /gateway/anomaly
# --------------------------------------------------------------------------


class GatewayAnomalyRequest(BaseModel):
    gateway: str
    baseline_failure_rate: float = Field(..., ge=0, le=1)
    current_failure_rate: float = Field(..., ge=0, le=1)
    current_volume: Optional[int] = Field(None, ge=0, description="Number of transactions the current rate is based on")
    transactions: Optional[List[TransactionRecord]] = Field(
        None,
        description="Recent transactions for this gateway, used to drill down into payment_method/bank/region.",
    )
    dimensions: Optional[List[str]] = Field(
        None, description="Drill-down order, defaults to ['payment_method', 'bank', 'region']"
    )
    min_sample_size: int = Field(5, ge=1)

    class Config:
        json_schema_extra = {
            "example": {
                "gateway": "Gateway A",
                "baseline_failure_rate": 0.023,
                "current_failure_rate": 0.178,
                "current_volume": 500,
                "transactions": [
                    {"status": "FAILED", "payment_method": "UPI", "bank": "Bank C", "region": "North", "count": 55},
                    {"status": "SUCCESS", "payment_method": "UPI", "bank": "Bank C", "region": "North", "count": 45},
                    {"status": "FAILED", "payment_method": "CARD", "bank": "Bank A", "region": "South", "count": 5},
                    {"status": "SUCCESS", "payment_method": "CARD", "bank": "Bank A", "region": "South", "count": 95},
                ],
            }
        }


class GatewayAnomalyResponse(BaseModel):
    gateway: str
    anomaly_detected: bool
    severity: str = Field(..., description="NONE | LOW | MEDIUM | HIGH | CRITICAL")
    baseline_failure_rate: float
    current_failure_rate: float
    delta: float
    ratio: float = Field(..., description="current / baseline, guarded against div-by-zero")
    root_cause_path: List[str] = Field(
        default_factory=list, description="e.g. ['Gateway A', 'UPI', 'Bank C', 'North region']"
    )
    root_causes: List[RootCauseSegment] = Field(default_factory=list)
    recommended_action: str


class HealthResponse(BaseModel):
    status: str = "ok"
    service: str = "recoverai-ml-service"

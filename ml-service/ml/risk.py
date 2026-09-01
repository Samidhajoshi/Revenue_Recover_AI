"""
Risk scoring for POST /risk.

Per section 9 of the implementation plan ("Do not over-engineer the ML"),
this is a transparent WEIGHTED SCORING function, not a trained model. It
needs no training data and works standalone from the first request.

--------------------------------------------------------------------------
RISK SCORE (0 = safe, 1 = high risk of permanent revenue loss)
--------------------------------------------------------------------------
risk_score = clip(
    0.35 * failure_reason_severity      # how hard this reason is to recover
  + 0.20 * previous_failures_norm       # repeated failures -> more likely to keep failing
  + 0.20 * historical_failure_rate      # this customer's own track record
  + 0.15 * amount_norm                  # bigger tickets are treated as more "at risk"
  - 0.10 * ltv_norm                     # high-LTV customers are slightly de-risked
, 0, 1)

Weights sum to 1.0 on the positive side, with a small (max 0.10) negative
adjustment for customer quality/LTV. All components are pre-normalized to
[0, 1] before weighting so no single input can dominate purely from scale.

--------------------------------------------------------------------------
RECOVERY PROBABILITY
--------------------------------------------------------------------------
Base probability is looked up per failure_reason, using the recovery-
simulator numbers from sections 3/13 of the plan (probability of the BEST
available action for that reason succeeding):

  temporary_decline  -> 0.60 (RETRY_PAYMENT)
  insufficient_funds -> 0.25 (RETRY_PAYMENT)
  expired_card       -> 0.55 (SEND_PAYMENT_LINK / payment update; blind
                               retry on an expired card is only ~5%, so we
                               recommend the update action instead)
  gateway_failure     -> 0.50 (RETRY_PAYMENT / ALTERNATE_METHOD after
                               transient gateway issues)
  abandoned_checkout -> 0.35 (SEND_PAYMENT_LINK)
  disputed            -> 0.05 (ESCALATE; not automatable)
  <unknown>            -> 0.40 (neutral default)

That base is then modulated:
  * previous_failures decay: each extra prior failure multiplies the
    probability by 0.92 (8% relative decay), floored at 50% of the base,
    reflecting that a customer/route that has already failed repeatedly is
    less likely to succeed on the next attempt.
  * customer_ltv boost: up to +5% relative, scaled by ltv_norm, reflecting
    that higher-value customers more often have valid, well-funded payment
    instruments and are more responsive to recovery messaging.
Result is clipped to [0, 1].
"""

from __future__ import annotations

from ml.schemas import RiskRequest, RiskResponse

# Severity of each failure reason: how hard it is to recover from, 0-1.
_FAILURE_SEVERITY = {
    "temporary_decline": 0.30,
    "insufficient_funds": 0.55,
    "expired_card": 0.70,
    "gateway_failure": 0.35,
    "abandoned_checkout": 0.60,
    "disputed": 0.90,
}
_DEFAULT_SEVERITY = 0.50

# Base recovery probability of the *best* available action for this reason.
_BASE_RECOVERY_PROBABILITY = {
    "temporary_decline": 0.60,
    "insufficient_funds": 0.25,
    "expired_card": 0.55,
    "gateway_failure": 0.50,
    "abandoned_checkout": 0.35,
    "disputed": 0.05,
}
_DEFAULT_RECOVERY_PROBABILITY = 0.40

_RECOMMENDED_ACTION = {
    "temporary_decline": "RETRY_PAYMENT",
    "insufficient_funds": "RETRY_PAYMENT",
    "expired_card": "SEND_PAYMENT_LINK",
    "gateway_failure": "ALTERNATE_METHOD",
    "abandoned_checkout": "SEND_PAYMENT_LINK",
    "disputed": "ESCALATE",
}
_DEFAULT_ACTION = "RETRY_PAYMENT"

# Normalization scales (documented, arbitrary-but-reasonable business scales)
_AMOUNT_SCALE = 50_000.0       # amounts above this are treated as "maximally large"
_LTV_SCALE = 100_000.0         # LTV above this is treated as "maximally high value"
_PREV_FAILURES_SCALE = 5.0     # 5+ previous failures = maximal risk contribution

_LOW_MEDIUM_BOUNDARY = 0.35
_MEDIUM_HIGH_BOUNDARY = 0.65


def _clip(value: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, value))


def _normalize_reason(failure_reason: str) -> str:
    return (failure_reason or "").strip().lower()


def score_risk(request: RiskRequest) -> RiskResponse:
    reason = _normalize_reason(request.failure_reason)

    severity = _FAILURE_SEVERITY.get(reason, _DEFAULT_SEVERITY)

    previous_failures_norm = _clip(request.previous_failures / _PREV_FAILURES_SCALE)

    total_history = request.successful_payments + request.previous_failures
    historical_failure_rate = (request.previous_failures / total_history) if total_history > 0 else 0.5

    amount_norm = _clip(request.amount / _AMOUNT_SCALE)
    ltv_norm = _clip(request.customer_ltv / _LTV_SCALE)

    risk_score = _clip(
        0.35 * severity
        + 0.20 * previous_failures_norm
        + 0.20 * historical_failure_rate
        + 0.15 * amount_norm
        - 0.10 * ltv_norm
    )

    if risk_score < _LOW_MEDIUM_BOUNDARY:
        risk_tier = "LOW"
    elif risk_score < _MEDIUM_HIGH_BOUNDARY:
        risk_tier = "MEDIUM"
    else:
        risk_tier = "HIGH"

    base_probability = _BASE_RECOVERY_PROBABILITY.get(reason, _DEFAULT_RECOVERY_PROBABILITY)

    decay = max(0.5, 0.92 ** request.previous_failures)
    ltv_boost = 1.0 + 0.05 * ltv_norm

    recovery_probability = _clip(base_probability * decay * ltv_boost)

    recommended_action = _RECOMMENDED_ACTION.get(reason, _DEFAULT_ACTION)

    return RiskResponse(
        risk_score=round(risk_score, 4),
        risk_tier=risk_tier,
        recovery_probability=round(recovery_probability, 4),
        recommended_action=recommended_action,
    )

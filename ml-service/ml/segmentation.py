"""
Lightweight customer segmentation helpers.

The implementation plan's FastAPI structure (section 9) lists a
segmentation.py module but does not define a dedicated endpoint for it.
It is kept here as a small, transparent utility (rule-based bucketing,
no trained model) that risk.py / future endpoints can reuse to label a
customer's value/reliability segment. Exposed only as a plain function for
now — not wired to a route — so it stays available without adding scope
beyond what section 9 actually asks for.
"""

from __future__ import annotations

_HIGH_LTV_THRESHOLD = 100_000.0
_MID_LTV_THRESHOLD = 25_000.0

_RELIABLE_SUCCESS_RATE = 0.85
_RISKY_SUCCESS_RATE = 0.5


def ltv_segment(customer_ltv: float) -> str:
    """Bucket a customer purely by lifetime value."""
    if customer_ltv >= _HIGH_LTV_THRESHOLD:
        return "HIGH_VALUE"
    if customer_ltv >= _MID_LTV_THRESHOLD:
        return "MID_VALUE"
    return "LOW_VALUE"


def reliability_segment(successful_payments: int, previous_failures: int) -> str:
    """Bucket a customer by their historical payment success rate."""
    total = successful_payments + previous_failures
    if total == 0:
        return "UNKNOWN"
    success_rate = successful_payments / total
    if success_rate >= _RELIABLE_SUCCESS_RATE:
        return "RELIABLE"
    if success_rate >= _RISKY_SUCCESS_RATE:
        return "MIXED"
    return "RISKY"


def customer_segment(customer_ltv: float, successful_payments: int, previous_failures: int) -> str:
    """Combined label, e.g. 'HIGH_VALUE_RELIABLE'."""
    return f"{ltv_segment(customer_ltv)}_{reliability_segment(successful_payments, previous_failures)}"

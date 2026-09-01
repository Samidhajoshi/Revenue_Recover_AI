"""
Root-cause diagnosis for POST /diagnose.

Per section 10 of the implementation plan, this is deliberately NOT
"complicated explainable ML". It is ranked failure-rate deltas from
group-by slicing over pandas, exactly as instructed:

    "The first implementation can use ranked failure-rate deltas instead
    of complicated explainable ML."

Approach
--------
1. Expand `transactions` (each row may carry a `count` weight for
   pre-aggregated data) into a pandas DataFrame.
2. Compute the overall failure rate across everything.
3. For every combination of 1..3 of the requested dimensions (default:
   bank, payment_method, region, gateway), group by that combination and
   compute the segment failure rate.
4. Drop segments smaller than `min_sample_size` (avoids noisy tiny
   segments, e.g. "Bank Z + Region Q" with 2 transactions).
5. Rank remaining segments by delta = segment_failure_rate - overall_rate
   (ties broken by sample size, favoring higher-confidence segments).
6. Return the top N as ranked root causes, e.g. "Bank C + UPI + North
   region" from the worked example in section 10.

Using combinations up to size 3 (rather than only single-dimension
group-bys) is what lets compound causes like "Bank C + UPI + North
region" surface, matching the example in section 5 / 10 of the plan.
"""

from __future__ import annotations

from itertools import combinations
from typing import List, Optional

import pandas as pd

from ml.schemas import RootCauseSegment, TransactionRecord

DEFAULT_DIMENSIONS = ["bank", "payment_method", "region", "gateway"]
MAX_COMBO_SIZE = 3


def _records_to_frame(transactions: List[TransactionRecord]) -> pd.DataFrame:
    rows = []
    for t in transactions:
        d = t.model_dump()
        d["is_failed"] = str(d.get("status", "")).strip().upper() == "FAILED"
        rows.append(d)
    df = pd.DataFrame(rows)
    return df


def _expand_by_count(df: pd.DataFrame) -> pd.DataFrame:
    """Expand aggregated rows (count > 1) into repeated rows so groupby
    counts behave like transaction-level data would."""
    if "count" not in df.columns:
        return df
    if (df["count"] <= 1).all():
        return df
    return df.loc[df.index.repeat(df["count"])].reset_index(drop=True)


def _label_for(dims: dict) -> str:
    parts = []
    for key, value in dims.items():
        if key == "region":
            parts.append(f"{value} region")
        else:
            parts.append(str(value))
    return " + ".join(parts)


def diagnose(
    transactions: List[TransactionRecord],
    dimensions: Optional[List[str]] = None,
    min_sample_size: int = 5,
    top_n: int = 5,
) -> tuple[int, int, float, List[RootCauseSegment]]:
    dims = dimensions or DEFAULT_DIMENSIONS

    raw_df = _records_to_frame(transactions)
    df = _expand_by_count(raw_df)

    total_transactions = int(len(df))
    total_failed = int(df["is_failed"].sum())
    overall_failure_rate = (total_failed / total_transactions) if total_transactions else 0.0

    # Only slice on dimensions that are actually present (non-null) in the data.
    available_dims = [d for d in dims if d in df.columns and df[d].notna().any()]

    segments: List[RootCauseSegment] = []
    seen_labels = set()

    for combo_size in range(1, min(MAX_COMBO_SIZE, len(available_dims)) + 1):
        for combo in combinations(available_dims, combo_size):
            combo = list(combo)
            grouped = df.dropna(subset=combo).groupby(combo, dropna=True)
            for key, group in grouped:
                key_tuple = key if isinstance(key, tuple) else (key,)
                sample_size = int(len(group))
                if sample_size < min_sample_size:
                    continue
                failed_count = int(group["is_failed"].sum())
                segment_rate = failed_count / sample_size
                delta = segment_rate - overall_failure_rate
                if delta <= 0:
                    continue  # only interested in segments that under-perform the baseline
                lift = (segment_rate / overall_failure_rate) if overall_failure_rate > 0 else float("inf")

                dims_dict = {dim: str(val) for dim, val in zip(combo, key_tuple)}
                label = _label_for(dims_dict)
                if label in seen_labels:
                    continue
                seen_labels.add(label)

                segments.append(
                    RootCauseSegment(
                        dimensions=dims_dict,
                        label=label,
                        sample_size=sample_size,
                        failed_count=failed_count,
                        segment_failure_rate=round(segment_rate, 4),
                        overall_failure_rate=round(overall_failure_rate, 4),
                        delta=round(delta, 4),
                        lift=round(lift, 4) if lift != float("inf") else lift,
                    )
                )

    # Rank: prefer larger delta; break ties with larger sample size (more confidence)
    # and more dimensions (more specific / compound root causes) for equal delta.
    segments.sort(key=lambda s: (s.delta, len(s.dimensions), s.sample_size), reverse=True)

    return total_transactions, total_failed, round(overall_failure_rate, 4), segments[:top_n]

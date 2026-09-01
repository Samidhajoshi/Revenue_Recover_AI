# RecoverAI ML Service

FastAPI ML service for RecoverAI (section 9 of `docs/IMPLEMENTATION_PLAN.txt`).

Layout:
```
ml-service/
  requirements.txt
  README.md
  ml/
    __init__.py
    main.py              # FastAPI app + routes
    schemas.py            # pydantic request/response models
    risk.py                # POST /risk  - weighted scoring
    diagnosis.py           # POST /diagnose - ranked failure-rate-delta root cause
    gateway_anomaly.py     # POST /gateway/anomaly - anomaly detection + drill-down
    segmentation.py        # rule-based customer segmentation helpers (utility, no route)
```

## Run

From the `ml-service/` directory:

```bash
pip install -r requirements.txt
uvicorn ml.main:app --reload
```

Docs at http://127.0.0.1:8000/docs

## Endpoints

- `GET /health`
- `POST /risk` — weighted risk scoring, no training data required. See the
  docstring in `ml/risk.py` for the exact weights and recovery-probability
  base rates (sourced from sections 3/13 of the plan).
- `POST /diagnose` — ranked failure-rate-delta root cause analysis via
  pandas group-by over up to 3-dimension combinations (bank, payment_method,
  region, gateway). See `ml/diagnosis.py`.
- `POST /gateway/anomaly` — compares current vs baseline gateway failure
  rate, flags severity, and (if `transactions` are supplied) drills down
  gateway -> payment_method -> bank -> region using the same logic as
  `/diagnose`. See `ml/gateway_anomaly.py`.

## Design notes / deviations

- All logic is transparent weighted scoring / statistical group-by slicing
  per the plan's explicit "Do not over-engineer the ML" instruction — no
  trained models in v1, even though scikit-learn is in requirements.txt for
  parity with the plan's tech stack list.
- `RiskResponse` includes an extra `recommended_action` field beyond the
  plan's stated risk_score/risk_tier/recovery_probability output, since the
  recovery_probability is computed *for* a specific best-available action
  and callers need to know which one.
- `segmentation.py` exists per the suggested file structure but is not
  wired to a route — the plan does not define a `/segment` endpoint, so it
  is kept as a small reusable utility instead of invented scope.

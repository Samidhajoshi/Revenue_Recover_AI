# RecoverAI — Autonomous Revenue Recovery Controller

RecoverAI detects revenue at risk — failed payments, failed subscription renewals, degrading payment gateways — diagnoses the cause, evaluates every plausible intervention side by side, executes only the one policy allows and expects to work best, and audits every step.

**Core principle:** AI decides what is likely to work. Policy decides what is allowed. The state machine decides what happens next.

## Results

Full 10,000-transaction / 1,000-subscription synthetic batch, agent vs. a naive baseline (every failed payment retried once, no diagnosis, no policy):

| | Baseline | RecoverAI |
|---|---|---|
| Recovery rate | — | **75%** |
| Revenue recovered | ₹2.58Cr | **₹9.69Cr** |
| Improvement | — | **+275.56%** |
| Policy violations | — | **0** |

## Architecture

```
React Dashboard (:5173)
        │ REST
        ▼
Spring Boot Core (:8082)
   ├── H2 database (file-based; MySQL is a config swap away)
   ├── State machine + Policy engine
   ├── Counterfactual evaluation engine
   └── Recovery simulator
        │
        ├──▶ FastAPI ML Service (:8000) — risk scoring, root-cause diagnosis
        └──▶ Groq LLM — recovery messages, promise-to-pay extraction
```

Four independent services, each owning one concern, talking over REST. The ML service and the LLM both degrade gracefully to an in-process fallback if unreachable — the agent loop never stalls on an external dependency.

### The agent loop

```
event arrives → create case → risk + diagnosis → generate candidate actions
   → estimate recovery probability per action → policy-filter → rank by expected recovery
   → execute ONLY the selected action → observe outcome
   → RECOVERED | retry (NEXT_INTERVENTION) | ESCALATED | STOPPED
```

Every arrow above is a transition in `StateMachineService`, and every transition writes an `AuditLog` row — nothing changes case state outside that one path, so the audit trail is complete by construction.

## What's in each service

### `backend/` — Spring Boot (Java 17)

The agent core: entities, state machine, policy engine, recovery simulator, CSV ingestion, and every REST endpoint.

- **State machine** — `DETECTED → DIAGNOSING → DIAGNOSED → DECIDING → POLICY_CHECK → INTERVENING → WAITING → RECOVERED/ESCALATED/STOPPED`, plus `NEXT_INTERVENTION` for retries.
- **Policy engine** — configurable rules: max retries, quiet hours, opt-out enforcement, minimum recovery-probability gate, high-value escalation, dispute handling. Nothing hardcoded — all of it lives in `application.yml` under `recoverai.policy.*`.
- **Counterfactual evaluation engine** (`CounterfactualEvaluationService` / `ActionRankingService`) — before executing anything, every plausible candidate action is scored (recovery probability × amount at risk = expected recovery), policy-filtered, and ranked. Only the winner ever executes; every other candidate is persisted as an `ActionEvaluation` row — an auditable "here's what we didn't do, and why." Once a case resolves, predicted vs. actual recovery is recorded on the winning row.
- **Recovery simulator** — deterministic-but-configurable per-(failure reason, action) success probabilities, seeded for reproducibility.

### `ml-service/` — FastAPI (Python)

Deliberately simple, not a trained model — weighted scoring for risk, statistical group-by slicing for root-cause diagnosis, threshold comparison for gateway anomaly detection.

- `POST /risk` — risk score, tier, recovery probability
- `POST /diagnose` — ranked failure-rate deltas across dimensions (bank × payment method × region × gateway)
- `POST /gateway/anomaly` — current vs. baseline failure rate with root-cause drill-down

### `frontend/` — React + Vite

- **Overview** — revenue at risk / recoverable / recovered, recovery rate ring, recovery-by-scenario and intervention-outcome charts
- **Recovery Cases** — filterable table (type, risk tier, status)
- **Case Details** — full decision trail: risk, diagnosis, the "what-if recovery analysis" table (every candidate action considered, its probability, expected recovery, and policy status — click any row to preview it as a hypothetical), audit timeline, LLM tools (generate a recovery message, extract a promise-to-pay)
- **Batch Simulation** — detect-only button, "RUN 10,000 CASES", baseline comparison, counterfactual metrics, CSV import

### `data_generator/` — Python

Synthetic dataset generator: 1,000 customers, 10,000 transactions, 1,000 subscriptions, 4 gateways (one deliberately degraded — Gateway A, UPI, Bank C, North region). Every row carries ground truth (expected action, expected outcome, recoverable amount) and is seeded for reproducibility. Output lands in `data_generator/output/`.

## LLM integration

Two jobs only — the LLM never makes a financial decision:

1. **Recovery messages** — a short, non-threatening Hinglish or English message inviting the customer to retry or update payment. `POST /api/agent/{id}/message`
2. **Promise-to-pay extraction** — structured JSON (intent, amount, ISO date) pulled from free customer text, validated by Spring Boot (positive amount, non-past date) before storage. `POST /api/agent/{id}/promise-to-pay`

Runs on Groq (`openai/gpt-oss-120b`, OpenAI-compatible chat-completions API, called via raw HTTP since Groq has no official Java SDK). Every response carries `llmUsed: true|false` — an honest report of whether the real API call succeeded, not a guess — and falls back to a templated message if the key is missing or the call fails.

## Running it locally

Three services, started in order. Each needs its own terminal (or run in the background).

```bash
# 1. ML service
cd ml-service
pip install -r requirements.txt
uvicorn ml.main:app --port 8000

# 2. Backend — set GROQ_API_KEY first for live LLM calls (optional; falls back to templates without it)
cd backend
export GROQ_API_KEY=gsk_...        # PowerShell: $env:GROQ_API_KEY="gsk_..."
mvn spring-boot:run                # serves :8082

# 3. Frontend
cd frontend
npm install
npm run dev                        # serves :5173
```

Then load the synthetic dataset (order matters — customers and gateways before transactions/subscriptions):

```bash
curl -F "file=@data_generator/output/customers.csv"     localhost:8082/api/import/customers
curl -F "file=@data_generator/output/gateways.csv"      localhost:8082/api/import/gateways
curl -F "file=@data_generator/output/transactions.csv"  localhost:8082/api/import/transactions
curl -F "file=@data_generator/output/subscriptions.csv" localhost:8082/api/import/subscriptions

# See live "revenue at risk" numbers before running anything:
curl -X POST localhost:8082/api/agent/detect

# Or run the full batch end to end:
curl -X POST localhost:8082/api/simulation/run
```

Open `http://localhost:5173` to browse the dashboard. The H2 database is file-based (`backend/data/recoverai.mv.db`), so data survives a restart — delete it to start fresh. Switching to MySQL is a five-line change in `backend/src/main/resources/application.yml`, already commented in place.

## Regenerating the synthetic dataset

```bash
cd data_generator
python run_all.py
```

Writes fresh CSVs to `data_generator/output/`. Distribution and outcome-probability assumptions live in `scenario_config.json`, not hardcoded in the scripts.

## Demo scenarios

Four scenarios from the original plan's demo script, each with a stable case ID in the current dataset:

| Scenario | Path | Outcome |
|---|---|---|
| Temporary decline | Detected → retry → succeeded | Recovered |
| Expired card (subscription) | Detected → payment-link message → paid | Recovered |
| Gateway degradation | 17.8% vs 2.3% baseline → root cause identified → reroute | Protected (₹2.95Cr) |
| Unsafe / disputed case | All automated actions blocked → escalate | Escalated, not retried |

## Key API endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/import/{customers\|gateways\|transactions\|subscriptions}` | CSV ingestion |
| POST | `/api/agent/detect` | Detection only — no execution |
| GET | `/api/dashboard/summary` | Live totals (revenue at risk, recovered, recovery rate) |
| GET | `/api/recovery` | List cases (filter by type / risk tier / status) |
| GET | `/api/recovery/{id}` | Case detail |
| POST | `/api/recovery/{id}/run` | Run one case through the full agent loop |
| GET | `/api/recovery/{id}/counterfactuals` | Every candidate action considered for a case |
| POST | `/api/recovery/{id}/evaluate` | Re-run counterfactual evaluation |
| GET | `/api/audit/{recoveryCaseId}` | Full audit trail for a case |
| POST | `/api/simulation/run` | Batch: detect + execute every case + baseline comparison |
| POST | `/api/agent/{id}/message` | LLM-generated recovery message |
| POST | `/api/agent/{id}/promise-to-pay` | LLM promise-to-pay extraction |

## What's not built

From the original plan's stretch list: checkout-abandonment recovery, B2B receivables chasing, voice-based recovery, and dynamic intervention optimization (a bandit/RL approach replacing the static configured probabilities once real outcome data accumulates).

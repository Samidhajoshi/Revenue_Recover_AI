# RecoverAI — Autonomous Revenue Recovery System

RecoverAI is a simple revenue recovery system that detects failed payments, analyzes why they failed, estimates recovery risk, evaluates possible recovery actions, applies business policies, and executes the safest action.

The goal is to reduce lost revenue while keeping recovery decisions explainable and policy-controlled.

## Features

- Detect failed payments and create recovery cases
- Calculate explainable risk scores and risk tiers
- Estimate recovery probability
- Diagnose high-failure segments by gateway, bank, payment method, and region
- Detect gateway-level anomalies
- Evaluate multiple recovery actions using counterfactual analysis
- Apply policy rules before execution
- Re-evaluate after failed interventions
- Track recovery attempts and final outcomes
- Generate customer-friendly recovery messages with an LLM
- Extract Promise-to-Pay information from customer messages
- Segment customers using LTV and payment reliability
- Provide a React dashboard for cases, customers, simulations, and analysis
### The agent loop

```
event arrives → create case → risk + diagnosis → generate candidate actions
   → estimate recovery probability per action → policy-filter → rank by expected recovery
   → execute ONLY the selected action → observe outcome
   → RECOVERED | retry (NEXT_INTERVENTION) | ESCALATED | STOPPED
```

Every arrow above is a transition in `StateMachineService`, and every transition writes an `AuditLog` row — nothing changes case state outside that one path, so the audit trail is complete by construction.

## Project structure

```
Revenue_Recovery/
├── backend/                  Spring Boot core (Java 17, Maven)
│   ├── src/main/java/com/recoverai/backend/
│   │   ├── config/           CORS, WebClient, policy properties, startup data seeder
│   │   ├── controller/       REST endpoints
│   │   ├── dto/               Request/response shapes (incl. dto/ml/ — ml-service contracts)
│   │   ├── entity/            JPA entities (RecoveryCase, Transaction, ActionEvaluation, ...)
│   │   ├── repository/        Spring Data JPA repositories
│   │   └── service/           State machine, policy engine, counterfactual engine, LLM client
│   ├── src/main/resources/
│   │   ├── application.yml    All configuration (policy thresholds, ML/LLM URLs, CORS, DB)
│   │   └── seed-data/         Bundled synthetic dataset, auto-loaded on first boot
│   ├── data/                  H2 database files (file-based, gitignored in spirit — local only)
│   ├── Dockerfile             Multi-stage build for deployment (e.g. Render)
│   └── pom.xml
│
├── ml-service/                FastAPI ML service (Python)
│   ├── ml/
│   │   ├── main.py            App entrypoint — uvicorn ml.main:app
│   │   ├── risk.py            Weighted risk scoring
│   │   ├── diagnosis.py       Failure-rate-delta root-cause analysis
│   │   ├── gateway_anomaly.py Gateway degradation detection
│   │   └── schemas.py         Pydantic request/response models (the source of truth for contracts)
│   └── requirements.txt
│
├── frontend/                  React + Vite dashboard
│   └── src/
│       ├── pages/              Overview, RecoveryCases, CaseDetails, Customers, BatchSimulation
│       ├── components/         Shared UI (Layout, StatusBanner, ...)
│       ├── api/                Axios client + endpoint functions
│       ├── hooks/               useApi data-fetching hook
│       └── utils/                Formatting helpers
│
├── data_generator/            Synthetic dataset generator (Python, seeded/config-driven)
│   ├── run_all.py
│   ├── scenario_config.json    Distribution + probability assumptions (not hardcoded)
│   └── output/                 Generated CSVs (customers, transactions, subscriptions, gateways)
│
└── docs/                       Original implementation plan documents
```

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

## Clone & run

```bash
git clone <this-repo-url> revenue-recovery
cd revenue-recovery
```

Three services, started in order. Each needs its own terminal (or run in the background). Requires Java 17+, Maven, Python 3.11+, and Node 18+.

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

Open `http://localhost:5173` — the backend auto-loads the bundled 10,000-transaction synthetic dataset on first boot (see `DataSeeder`, `backend/src/main/resources/seed-data/`), so there's nothing to import manually. From the dashboard: **Batch Simulation → RUN 10,000 CASES** runs the full agent loop end to end, or **Detect at-risk cases** to see live "revenue at risk" numbers before anything executes.

The H2 database is file-based (`backend/data/recoverai.mv.db`), so data survives a restart. Delete it to start over from scratch — the seeder will reload the bundled dataset on the next boot. Switching to MySQL/Postgres is a config swap in `backend/src/main/resources/application.yml`, already commented in place. Set `SEED_ON_STARTUP=false` to disable auto-seeding (e.g. once you're working with real imported data).

To load a different dataset instead of the bundled one:

```bash
curl -F "file=@data_generator/output/customers.csv"     localhost:8082/api/import/customers
curl -F "file=@data_generator/output/gateways.csv"      localhost:8082/api/import/gateways
curl -F "file=@data_generator/output/transactions.csv"  localhost:8082/api/import/transactions
curl -F "file=@data_generator/output/subscriptions.csv" localhost:8082/api/import/subscriptions
```

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


## Architecture

```text
                    React Frontend
                          |
                          v
                   Spring Boot API
                          |
        +-----------------+------------------+
        |                 |                  |
        v                 v                  v
   Risk Service      Diagnosis          Gateway Anomaly
        |                 |                  |
        +-----------------+------------------+
                          |
                          v
                Counterfactual Evaluation
                          |
                          v
                    Policy Engine
                          |
                          v
                  Agent Orchestrator
                          |
                          v
                      H2 DB

                    LLM Service
                 /              \
       Recovery Messages    Promise Extraction

```
## System Architecture
<img width="4255" height="4449" alt="mermaid-diagram-1788327852011" src="https://github.com/user-attachments/assets/ebdad0da-8e6b-4c45-97e8-ea3c0a23198a" />


## Snapshots 

## Dashboard
<img width="1916" height="1026" alt="Screenshot 2026-09-03 103628" src="https://github.com/user-attachments/assets/bc8e124e-a40e-451a-ad44-f1079935b3ac" />

## Batch Simulation 
<img width="1912" height="1012" alt="Screenshot 2026-09-03 103836" src="https://github.com/user-attachments/assets/46acbaed-1d9d-41d1-a899-c9665fc4f1d8" />

## Customers 
<img width="1915" height="1037" alt="Screenshot 2026-09-03 103815" src="https://github.com/user-attachments/assets/1cf7ada2-c110-4f9d-accf-385acc18042c" />

## Cases 
<img width="1913" height="1032" alt="Screenshot 2026-09-03 103725" src="https://github.com/user-attachments/assets/4db25a12-c1c4-4653-9294-037d1e9bef03" />

## Try at
https://revenue-recover-ai.vercel.app/

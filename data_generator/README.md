# RecoverAI Synthetic Data Generator

Generates deterministic synthetic data for the RecoverAI revenue-recovery
project, per `docs/IMPLEMENTATION_PLAN.txt` sections 6, 12, and 13.

## Run

```
cd data_generator
python run_all.py
```

Or run the four scripts individually, in this order (transactions and
subscriptions reference customer ids, so customers must run first):

```
python generate_customers.py
python generate_gateways.py
python generate_transactions.py
python generate_subscriptions.py
```

## Output

All CSVs land in `./output/`:

| File | Rows | Description |
|---|---|---|
| `customers.csv` | 1,000 | Customer profiles, LTV, payment history, segment |
| `gateways.csv` | 4 | Payment gateways; Gateway A is deliberately degraded |
| `transactions.csv` | 10,000 | Payment attempts with ground-truth recovery labels |
| `subscriptions.csv` | 1,000 | Subscriptions with ground-truth recovery labels |

## Configuration

All distribution percentages, outcome probabilities, gateway degradation
signal, and value ranges live in `scenario_config.json` — nothing is
hardcoded in the scripts. Edit that file to change scenarios; the scripts
will pick up the new values on the next run.

Key settings:
- `seed`: master RNG seed (default 42) for full reproducibility. Each
  script derives its own independent stream from `seed` (e.g. `seed+1` for
  transactions) so re-running any single script is deterministic without
  depending on run order side effects.
- `transactions.distribution`: the 60/10/5/5/5/5/5/5 split from section 12.
- `transactions.recovery_actions` / `subscriptions.recovery_actions`: the
  recovery-probability assumptions from section 13 (e.g. temporary_decline
  + retry ~60%, insufficient_funds + retry ~25%, expired_card + retry ~5%,
  expired_card + payment_update ~55%).
- `gateways[0]` ("Gateway A"): baseline_failure_rate 2.3% vs
  current_failure_rate 17.8%, with `degradation_signal` concentrating the
  induced failures on UPI + Bank C + North (the section 5/10 example) so
  the root-cause diagnosis has a real signal to detect.

## Ground truth

Every `transactions.csv` and `subscriptions.csv` row carries four
ground-truth columns even when the record is already successful/active
(in which case `recoverable=False` and `expected_amount_recovered=0`):

- `expected_recovery_action` — the action the policy/agent should pick
  (`RETRY_PAYMENT`, `SEND_PAYMENT_LINK`, `ALTERNATE_METHOD`, `ESCALATE`,
  `NONE`, ...)
- `expected_outcome` — `ALREADY_SUCCESSFUL` / `RECOVERED` / `NOT_RECOVERED`
  / `STOPPED` / `PAUSED`
- `recoverable` — boolean, drawn from the configured recovery probability
  for that failure reason + action
- `expected_amount_recovered` — the transaction/subscription amount if
  `recoverable` else `0`

These labels are not hand-tuned to look good; they are sampled from the
probabilities in `scenario_config.json`, so re-seeding or editing the
config changes them honestly (per section 13: "Do not hardcode successful
outcomes just to make the numbers look good").

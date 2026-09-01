"""
generate_subscriptions.py
Generates 1,000 synthetic subscriptions for RecoverAI.

Fields (per IMPLEMENTATION_PLAN.txt section 6 - DATABASE DESIGN / SUBSCRIPTIONS):
id, customer_id, amount, billing_cycle, next_payment_date, status,
payment_method, failure_reason, retry_count, created_at

Ground truth columns (section 12), same convention as transactions, driven
by scenario_config.json's subscriptions.recovery_actions:
expected_recovery_action, expected_outcome, recoverable, expected_amount_recovered

Scenario B (section 4) drives the EXPIRED_CARD handling: a blind retry
only succeeds ~5-25% of the time depending on reason, while sending a
payment-update / payment link (SEND_PAYMENT_LINK) recovers ~40-55%.
retry_count >= max_retry_count forces ESCALATE, matching the policy rule
in section 8 ("retry_count >= 3 -> STOP / ESCALATE").

Deterministic: seeded from scenario_config.json["seed"].
Requires: ./output/customers.csv (run generate_customers.py first)
Output: ./output/subscriptions.csv
"""
import csv
import json
import os
import random
from datetime import datetime, timedelta

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "scenario_config.json")
OUTPUT_DIR = os.path.join(BASE_DIR, "output")
CUSTOMERS_PATH = os.path.join(OUTPUT_DIR, "customers.csv")
OUTPUT_PATH = os.path.join(OUTPUT_DIR, "subscriptions.csv")


def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def load_customer_ids():
    if not os.path.exists(CUSTOMERS_PATH):
        raise FileNotFoundError(
            f"{CUSTOMERS_PATH} not found. Run generate_customers.py first."
        )
    with open(CUSTOMERS_PATH, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        return [row["id"] for row in reader]


def weighted_choice(rng, weighted_dict):
    keys = list(weighted_dict.keys())
    weights = list(weighted_dict.values())
    return rng.choices(keys, weights=weights, k=1)[0]


def weighted_choice_list(rng, options, weights):
    return rng.choices(options, weights=weights, k=1)[0]


def random_past_datetime(rng, days_back=730):
    now = datetime(2026, 8, 26)
    delta_days = rng.randint(30, days_back)
    return now - timedelta(days=delta_days, seconds=rng.randint(0, 86399))


def ground_truth_for(rng, status, failure_reason_key, retry_count, amount, sub_cfg):
    """failure_reason_key is the lowercase config key (e.g. 'expired_card'),
    only meaningful when status == 'past_due'."""
    if status != "past_due":
        outcome = {
            "active": "ALREADY_SUCCESSFUL",
            "cancelled": "STOPPED",
            "paused": "PAUSED",
        }[status]
        return {
            "expected_recovery_action": "NONE",
            "expected_outcome": outcome,
            "recoverable": False,
            "expected_amount_recovered": 0,
        }

    if retry_count >= sub_cfg["max_retry_count"]:
        recovery_probability = 0.10  # policy forces escalation after max retries
        expected_recovery_action = "ESCALATE"
    else:
        action_cfg = sub_cfg["recovery_actions"][failure_reason_key]
        recovery_probability = action_cfg["recovery_probability"]
        expected_recovery_action = action_cfg["expected_recovery_action"]

    recoverable = rng.random() < recovery_probability
    return {
        "expected_recovery_action": expected_recovery_action,
        "expected_outcome": "RECOVERED" if recoverable else "NOT_RECOVERED",
        "recoverable": recoverable,
        "expected_amount_recovered": round(amount, 2) if recoverable else 0,
    }


def generate_subscriptions(config, customer_ids):
    rng = random.Random(config["seed"] + 2)  # independent stream
    sub_cfg = config["subscriptions"]
    payment_methods = config["transactions"]["payment_methods"]
    amount_lo, amount_hi = sub_cfg["amount_range"]
    billing_cycles = sub_cfg["billing_cycles"]
    billing_weights = sub_cfg["billing_cycle_weights"]
    now = datetime(2026, 8, 26)

    subscriptions = []
    for i in range(1, sub_cfg["count"] + 1):
        sub_id = f"SUB{i:05d}"
        customer_id = rng.choice(customer_ids)
        amount = round(rng.uniform(amount_lo, amount_hi), 2)
        billing_cycle = weighted_choice_list(rng, billing_cycles, billing_weights)
        payment_method = rng.choice(payment_methods)
        created_at = random_past_datetime(rng)

        status = weighted_choice(rng, sub_cfg["status_distribution"])

        failure_reason_key = ""
        retry_count = 0
        failure_reason = ""
        next_payment_date = ""

        if status == "active":
            next_payment_date = (now + timedelta(days=rng.randint(1, 30))).strftime("%Y-%m-%d")
        elif status == "past_due":
            failure_reason_key = weighted_choice(rng, sub_cfg["failure_reasons_when_past_due"])
            failure_reason = failure_reason_key.upper()
            retry_count = rng.randint(0, sub_cfg["max_retry_count"] + 1)
            next_payment_date = (now - timedelta(days=rng.randint(1, 45))).strftime("%Y-%m-%d")
        # cancelled / paused: next_payment_date left blank, no failure reason

        gt = ground_truth_for(rng, status, failure_reason_key, retry_count, amount, sub_cfg)

        subscriptions.append({
            "id": sub_id,
            "customer_id": customer_id,
            "amount": amount,
            "billing_cycle": billing_cycle,
            "next_payment_date": next_payment_date,
            "status": status.upper(),
            "payment_method": payment_method,
            "failure_reason": failure_reason,
            "retry_count": retry_count,
            "created_at": created_at.strftime("%Y-%m-%d %H:%M:%S"),
            "expected_recovery_action": gt["expected_recovery_action"],
            "expected_outcome": gt["expected_outcome"],
            "recoverable": gt["recoverable"],
            "expected_amount_recovered": gt["expected_amount_recovered"],
        })

    return subscriptions


def write_csv(subscriptions):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    fieldnames = [
        "id", "customer_id", "amount", "billing_cycle", "next_payment_date",
        "status", "payment_method", "failure_reason", "retry_count", "created_at",
        "expected_recovery_action", "expected_outcome", "recoverable",
        "expected_amount_recovered",
    ]
    with open(OUTPUT_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(subscriptions)


def main():
    config = load_config()
    customer_ids = load_customer_ids()
    subscriptions = generate_subscriptions(config, customer_ids)
    write_csv(subscriptions)
    print(f"Generated {len(subscriptions)} subscriptions -> {OUTPUT_PATH}")


if __name__ == "__main__":
    main()

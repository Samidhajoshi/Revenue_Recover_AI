"""
generate_transactions.py
Generates 10,000 synthetic payment transactions for RecoverAI.

Fields (per IMPLEMENTATION_PLAN.txt section 6 - DATABASE DESIGN / TRANSACTIONS):
id, customer_id, amount, currency, payment_method, gateway, bank, region,
status, failure_reason, attempt_number, created_at, updated_at

Distribution (section 12 - SYNTHETIC DATA GENERATOR):
60% successful, 10% temporary decline, 5% insufficient funds, 5% expired
card, 5% gateway failure, 5% abandoned checkout, 5% repeated failure,
5% ambiguous/edge cases.

Ground truth columns (section 12/13), driven by scenario_config.json so the
outcome probabilities are configurable/reproducible rather than hardcoded:
expected_recovery_action, expected_outcome, recoverable, expected_amount_recovered

Gateway A is deliberately degraded in scenario_config.json (baseline 2.3%
-> current 17.8% failure). For the "gateway_failure" category, transactions
are concentrated on Gateway A + UPI + Bank C + North (per the section 5/10
root-cause example) so the gateway-degradation diagnosis has a real signal.

Deterministic: seeded from scenario_config.json["seed"].
Requires: ./output/customers.csv (run generate_customers.py first)
Output: ./output/transactions.csv
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
OUTPUT_PATH = os.path.join(OUTPUT_DIR, "transactions.csv")

CATEGORY_TO_FAILURE_REASON = {
    "successful": "",
    "temporary_decline": "TEMPORARY_DECLINE",
    "insufficient_funds": "INSUFFICIENT_FUNDS",
    "expired_card": "EXPIRED_CARD",
    "gateway_failure": "GATEWAY_FAILURE",
    "abandoned_checkout": "ABANDONED_CHECKOUT",
    "repeated_failure": "REPEATED_FAILURE",
    "ambiguous": "AMBIGUOUS",
}

CATEGORY_TO_STATUS = {
    "successful": "SUCCESS",
    "temporary_decline": "FAILED",
    "insufficient_funds": "FAILED",
    "expired_card": "FAILED",
    "gateway_failure": "FAILED",
    "abandoned_checkout": "ABANDONED",
    "repeated_failure": "FAILED",
    "ambiguous": "FAILED",
}


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


def build_category_sequence(rng, distribution, total):
    """Turn percentage distribution into an exact-count, shuffled list of
    category labels summing to `total` (rounding remainder goes to the
    largest bucket so counts always add up exactly)."""
    counts = {cat: int(round(pct * total)) for cat, pct in distribution.items()}
    diff = total - sum(counts.values())
    if diff != 0:
        largest_cat = max(distribution, key=distribution.get)
        counts[largest_cat] += diff

    sequence = []
    for cat, n in counts.items():
        sequence.extend([cat] * n)
    rng.shuffle(sequence)
    return sequence, counts


def random_datetime(rng, days_back=365):
    now = datetime(2026, 8, 26)
    delta_days = rng.randint(0, days_back)
    delta_seconds = rng.randint(0, 86399)
    return now - timedelta(days=delta_days, seconds=delta_seconds)


def assign_gateway_context(rng, category, txn_cfg, gateways_cfg):
    degraded_gw = next(g for g in gateways_cfg if g.get("degraded"))
    signal = degraded_gw["degradation_signal"]

    if category == "gateway_failure" and rng.random() < signal["concentration"]:
        gateway = degraded_gw["name"]
        payment_method = signal["payment_method"]
        bank = signal["bank"]
        region = signal["region"]
    else:
        gateway = rng.choice([g["name"] for g in gateways_cfg])
        payment_method = rng.choice(txn_cfg["payment_methods"])
        bank = rng.choice(txn_cfg["banks"])
        region = rng.choice(txn_cfg["regions"])

    return gateway, payment_method, bank, region


def ground_truth_for(rng, category, amount, recovery_actions_cfg):
    if category == "successful":
        return {
            "expected_recovery_action": "NONE",
            "expected_outcome": "ALREADY_SUCCESSFUL",
            "recoverable": False,
            "expected_amount_recovered": 0,
        }

    action_cfg = recovery_actions_cfg[category]
    recovery_probability = action_cfg["recovery_probability"]
    recoverable = rng.random() < recovery_probability

    return {
        "expected_recovery_action": action_cfg["expected_recovery_action"],
        "expected_outcome": "RECOVERED" if recoverable else "NOT_RECOVERED",
        "recoverable": recoverable,
        "expected_amount_recovered": round(amount, 2) if recoverable else 0,
    }


def generate_transactions(config, customer_ids):
    rng = random.Random(config["seed"] + 1)  # offset seed: independent stream from customers
    txn_cfg = config["transactions"]
    gateways_cfg = config["gateways"]
    recovery_actions_cfg = txn_cfg["recovery_actions"]
    amount_lo, amount_hi = txn_cfg["amount_range"]

    sequence, counts = build_category_sequence(rng, txn_cfg["distribution"], txn_cfg["count"])

    transactions = []
    for i, category in enumerate(sequence, start=1):
        txn_id = f"TXN{i:06d}"
        customer_id = rng.choice(customer_ids)
        amount = round(rng.uniform(amount_lo, amount_hi), 2)
        currency = config["currency"]

        gateway, payment_method, bank, region = assign_gateway_context(
            rng, category, txn_cfg, gateways_cfg
        )

        status = CATEGORY_TO_STATUS[category]
        failure_reason = CATEGORY_TO_FAILURE_REASON[category]

        if category == "repeated_failure":
            attempt_number = rng.randint(3, 6)
        elif category == "successful":
            attempt_number = 1
        else:
            attempt_number = rng.randint(1, 2)

        created_at = random_datetime(rng)
        updated_at = created_at + timedelta(minutes=rng.randint(0, 180))

        gt = ground_truth_for(rng, category, amount, recovery_actions_cfg)

        transactions.append({
            "id": txn_id,
            "customer_id": customer_id,
            "amount": amount,
            "currency": currency,
            "payment_method": payment_method,
            "gateway": gateway,
            "bank": bank,
            "region": region,
            "status": status,
            "failure_reason": failure_reason,
            "attempt_number": attempt_number,
            "created_at": created_at.strftime("%Y-%m-%d %H:%M:%S"),
            "updated_at": updated_at.strftime("%Y-%m-%d %H:%M:%S"),
            "expected_recovery_action": gt["expected_recovery_action"],
            "expected_outcome": gt["expected_outcome"],
            "recoverable": gt["recoverable"],
            "expected_amount_recovered": gt["expected_amount_recovered"],
        })

    return transactions, counts


def write_csv(transactions):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    fieldnames = [
        "id", "customer_id", "amount", "currency", "payment_method", "gateway",
        "bank", "region", "status", "failure_reason", "attempt_number",
        "created_at", "updated_at",
        "expected_recovery_action", "expected_outcome", "recoverable",
        "expected_amount_recovered",
    ]
    with open(OUTPUT_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(transactions)


def main():
    config = load_config()
    customer_ids = load_customer_ids()
    transactions, counts = generate_transactions(config, customer_ids)
    write_csv(transactions)
    print(f"Generated {len(transactions)} transactions -> {OUTPUT_PATH}")
    print(f"Category breakdown: {counts}")


if __name__ == "__main__":
    main()

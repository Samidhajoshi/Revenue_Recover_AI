"""
generate_gateways.py
Generates the payment-gateway roster for RecoverAI.

Fields (per IMPLEMENTATION_PLAN.txt section 6 - DATABASE DESIGN / GATEWAYS):
id, name, success_rate, failure_rate, baseline_failure_rate, status,
cost_per_transaction, last_updated

Gateway A is deliberately configured as degraded (baseline 2.3% -> current
17.8% failure rate) so the gateway-degradation root-cause diagnosis
(section 5/10) has a real signal to find. generate_transactions.py reads
this same scenario_config.json and concentrates Gateway A's failures on the
UPI + Bank C + North combination described there.

Deterministic: seeded from scenario_config.json["seed"].
Output: ./output/gateways.csv
"""
import csv
import json
import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "scenario_config.json")
OUTPUT_DIR = os.path.join(BASE_DIR, "output")
OUTPUT_PATH = os.path.join(OUTPUT_DIR, "gateways.csv")

LAST_UPDATED = "2026-08-26 09:00:00"


def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def generate_gateways(config):
    gateways = []
    for gw in config["gateways"]:
        current_failure_rate = gw["current_failure_rate"]
        gateways.append({
            "id": gw["id"],
            "name": gw["name"],
            "success_rate": round(1 - current_failure_rate, 4),
            "failure_rate": round(current_failure_rate, 4),
            "baseline_failure_rate": round(gw["baseline_failure_rate"], 4),
            "status": gw["status"],
            "cost_per_transaction": gw["cost_per_transaction"],
            "last_updated": LAST_UPDATED,
        })
    return gateways


def write_csv(gateways):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    fieldnames = [
        "id", "name", "success_rate", "failure_rate", "baseline_failure_rate",
        "status", "cost_per_transaction", "last_updated",
    ]
    with open(OUTPUT_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(gateways)


def main():
    config = load_config()
    gateways = generate_gateways(config)
    write_csv(gateways)
    print(f"Generated {len(gateways)} gateways -> {OUTPUT_PATH}")


if __name__ == "__main__":
    main()

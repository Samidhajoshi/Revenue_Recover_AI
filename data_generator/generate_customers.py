"""
generate_customers.py
Generates 1,000 synthetic customers for RecoverAI.

Fields (per IMPLEMENTATION_PLAN.txt section 6 - DATABASE DESIGN / CUSTOMERS):
id, name, email, phone, ltv, total_payments, successful_payments,
failed_payments, segment, opted_out, created_at

Deterministic: seeded from scenario_config.json["seed"].
Output: ./output/customers.csv
"""
import csv
import json
import os
import random
from datetime import datetime, timedelta

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "scenario_config.json")
OUTPUT_DIR = os.path.join(BASE_DIR, "output")
OUTPUT_PATH = os.path.join(OUTPUT_DIR, "customers.csv")

FIRST_NAMES = [
    "Aarav", "Vivaan", "Aditya", "Vihaan", "Arjun", "Sai", "Reyansh", "Krishna",
    "Ishaan", "Rohan", "Ananya", "Diya", "Aadhya", "Saanvi", "Myra", "Anika",
    "Priya", "Neha", "Kavya", "Riya", "Karan", "Rahul", "Amit", "Sanjay",
    "Pooja", "Sneha", "Meera", "Nisha", "Vikram", "Arun", "Deepak", "Suresh",
    "Lakshmi", "Divya", "Rakesh", "Manoj", "Anjali", "Shreya", "Varun", "Nikhil",
]
LAST_NAMES = [
    "Sharma", "Verma", "Patel", "Gupta", "Kumar", "Singh", "Rao", "Nair",
    "Iyer", "Reddy", "Mehta", "Joshi", "Desai", "Kapoor", "Malhotra", "Chopra",
    "Bansal", "Agarwal", "Pillai", "Menon", "Bose", "Chatterjee", "Das", "Mishra",
]
EMAIL_DOMAINS = ["gmail.com", "yahoo.com", "outlook.com", "rediffmail.com", "hotmail.com"]


def load_config():
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def weighted_choice(rng, weighted_dict):
    keys = list(weighted_dict.keys())
    weights = list(weighted_dict.values())
    return rng.choices(keys, weights=weights, k=1)[0]


def random_datetime(rng, days_back=730):
    now = datetime(2026, 8, 26)  # anchor date for reproducibility
    delta_days = rng.randint(0, days_back)
    delta_seconds = rng.randint(0, 86399)
    dt = now - timedelta(days=delta_days, seconds=delta_seconds)
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def generate_customers(config):
    rng = random.Random(config["seed"])
    cust_cfg = config["customers"]
    count = cust_cfg["count"]
    segments = cust_cfg["segments"]
    opt_out_rate = cust_cfg["opt_out_rate"]
    ltv_ranges = cust_cfg["ltv_range_by_segment"]

    used_emails = set()
    customers = []

    for i in range(1, count + 1):
        cust_id = f"CUST{i:05d}"
        first = rng.choice(FIRST_NAMES)
        last = rng.choice(LAST_NAMES)
        name = f"{first} {last}"

        base_email = f"{first.lower()}.{last.lower()}{i}"
        email = f"{base_email}@{rng.choice(EMAIL_DOMAINS)}"
        while email in used_emails:
            base_email = f"{base_email}{rng.randint(1, 999)}"
            email = f"{base_email}@{rng.choice(EMAIL_DOMAINS)}"
        used_emails.add(email)

        phone = f"9{rng.randint(100000000, 999999999)}"

        segment = weighted_choice(rng, segments)
        lo, hi = ltv_ranges[segment]
        ltv = round(rng.uniform(lo, hi), 2)

        # Payment history correlated loosely with segment.
        if segment == "high_value":
            total_payments = rng.randint(20, 120)
        elif segment == "regular":
            total_payments = rng.randint(5, 40)
        elif segment == "at_risk":
            total_payments = rng.randint(3, 20)
        else:  # new
            total_payments = rng.randint(0, 4)

        if segment == "at_risk":
            failure_rate = rng.uniform(0.25, 0.55)
        elif segment == "new":
            failure_rate = rng.uniform(0.10, 0.35)
        else:
            failure_rate = rng.uniform(0.02, 0.15)

        failed_payments = round(total_payments * failure_rate)
        successful_payments = max(0, total_payments - failed_payments)

        opted_out = rng.random() < opt_out_rate
        created_at = random_datetime(rng)

        customers.append({
            "id": cust_id,
            "name": name,
            "email": email,
            "phone": phone,
            "ltv": ltv,
            "total_payments": total_payments,
            "successful_payments": successful_payments,
            "failed_payments": failed_payments,
            "segment": segment,
            "opted_out": opted_out,
            "created_at": created_at,
        })

    return customers


def write_csv(customers):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    fieldnames = [
        "id", "name", "email", "phone", "ltv", "total_payments",
        "successful_payments", "failed_payments", "segment", "opted_out",
        "created_at",
    ]
    with open(OUTPUT_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(customers)


def main():
    config = load_config()
    customers = generate_customers(config)
    write_csv(customers)
    print(f"Generated {len(customers)} customers -> {OUTPUT_PATH}")


if __name__ == "__main__":
    main()

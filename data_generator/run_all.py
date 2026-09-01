"""
run_all.py
Runs the RecoverAI synthetic data generators in the required order
(customers and gateways have no dependencies; transactions and
subscriptions both reference customer ids, so customers must run first).

Usage:
    python run_all.py

Output CSVs land in ./output/:
    customers.csv, transactions.csv, subscriptions.csv, gateways.csv
"""
import subprocess
import sys
import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

STEPS = [
    "generate_customers.py",
    "generate_gateways.py",
    "generate_transactions.py",
    "generate_subscriptions.py",
]


def main():
    for script in STEPS:
        path = os.path.join(BASE_DIR, script)
        print(f"\n=== Running {script} ===")
        result = subprocess.run([sys.executable, path], cwd=BASE_DIR)
        if result.returncode != 0:
            print(f"{script} failed with exit code {result.returncode}. Stopping.")
            sys.exit(result.returncode)
    print("\nAll generators completed. See ./output/ for CSVs.")


if __name__ == "__main__":
    main()

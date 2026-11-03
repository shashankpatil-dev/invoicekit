"""TEMPORARY: prints one table row per numbering load test phase, from the files run.sh writes."""

import json
import sys
from pathlib import Path

HEADERS = (
    "Phase", "Requests", "Achieved rps", "Failed", "503s", "Dropped", "p50", "p95", "p99", "Max",
    "Invoices", "Reconciliation", "PostgreSQL cores", "App cores",
)
LATENCIES = ("med", "p(95)", "p(99)", "max")


def metric(metrics, name, field):
    return metrics.get(name, {}).get("values", {}).get(field, 0)


def summarize(results, phase, postgres_cpu_usec, app_cpu_usec):
    """Builds one phase's row from its k6 summary, its reconciliation and its CPU use."""
    summary = json.loads((results / f"{phase}.json").read_text())
    reconciliation = json.loads((results / f"{phase}-reconciliation.json").read_text())
    metrics = summary["metrics"]
    seconds = summary["state"]["testRunDurationMs"] / 1000
    problems = len(reconciliation["problems"])
    return (
        phase,
        str(metric(metrics, "http_reqs", "count")),
        f"{metric(metrics, 'http_reqs', 'rate'):.1f}",
        str(metric(metrics, "http_req_failed", "passes")),
        str(metric(metrics, "busy_responses", "count")),
        str(metric(metrics, "dropped_iterations", "count")),
        *(f"{metric(metrics, 'http_req_duration', stat):.1f}ms" for stat in LATENCIES),
        str(reconciliation["invoices"]),
        "OK" if problems == 0 else f"{problems} broken series",
        f"{postgres_cpu_usec / 1_000_000 / seconds:.2f}",
        f"{app_cpu_usec / 1_000_000 / seconds:.2f}",
    )


def main():
    results = Path(sys.argv[1])
    print("| " + " | ".join(HEADERS) + " |")
    print("|" + "---|" * len(HEADERS))
    # Each line is: phase, PostgreSQL CPU usec, PostgreSQL peak memory, app CPU usec, app peak memory.
    for line in (results / "resources.txt").read_text().splitlines():
        phase, postgres_cpu_usec, _, app_cpu_usec, _ = line.split()
        row = summarize(results, phase, int(postgres_cpu_usec), int(app_cpu_usec))
        print("| " + " | ".join(row) + " |")


if __name__ == "__main__":
    main()

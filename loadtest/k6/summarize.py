"""Prints a results table from the files written by run.sh."""

import json
import sys
from pathlib import Path

HEADERS = ("Phase", "Requests", "Achieved rps", "Failed", "Dropped", "p50", "p95", "p99", "Max", "CPU/PDF", "Cores", "Peak memory")


def read_resources(results):
    """Reads each phase's CPU use and peak memory from resources.txt."""
    rows = (line.split() for line in (results / "resources.txt").read_text().splitlines())
    return {phase: (int(cpu_usec), int(memory)) for phase, cpu_usec, memory in rows}


def metric(metrics, name, field, default=0):
    return metrics.get(name, {}).get("values", {}).get(field, default)


def summarize(results, phase, resources):
    metrics = json.loads((results / f"{phase}.json").read_text())["metrics"]
    requests = metric(metrics, "http_reqs", "count")
    seconds = requests / metric(metrics, "http_reqs", "rate", 1)
    cpu_usec, memory = resources[phase]
    return (
        phase,
        str(requests),
        f"{metric(metrics, 'http_reqs', 'rate'):.1f}",
        str(metric(metrics, "http_req_failed", "passes")),
        str(metric(metrics, "dropped_iterations", "count")),
        f"{metric(metrics, 'http_req_duration', 'med'):.1f}ms",
        f"{metric(metrics, 'http_req_duration', 'p(95)'):.1f}ms",
        f"{metric(metrics, 'http_req_duration', 'p(99)'):.1f}ms",
        f"{metric(metrics, 'http_req_duration', 'max'):.1f}ms",
        f"{cpu_usec / 1000 / max(requests, 1):.1f}ms",
        f"{cpu_usec / 1_000_000 / seconds:.2f}",
        f"{memory / 1024 / 1024:.0f}MiB",
    )


def main():
    results = Path(sys.argv[1])
    resources = read_resources(results)
    rows = [summarize(results, phase, resources) for phase in resources]
    print("| " + " | ".join(HEADERS) + " |")
    print("|" + "---|" * len(HEADERS))
    for row in rows:
        print("| " + " | ".join(row) + " |")


if __name__ == "__main__":
    main()

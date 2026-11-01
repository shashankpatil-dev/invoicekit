"""Prints one results row per minute from a gradual load test."""

import json
import sys

MINUTES = 5


def main():
    metrics = json.load(open(sys.argv[1], encoding="utf-8"))["metrics"]
    print("| Minute | Requests | Rate | Failed | p50 | p95 | p99 | Max |")
    print("|---|---|---|---|---|---|---|---|")
    for minute in range(1, MINUTES + 1):
        duration = metrics[f"minute_{minute}_duration"]["values"]
        failed = metrics[f"minute_{minute}_failed"]["values"]
        requests = metrics[f"minute_{minute}_requests"]["values"]["count"]
        print(
            f"| {minute} | {requests} | {requests / 60:.1f} | {failed['passes']} | {duration['med']:.1f}ms"
            f" | {duration['p(95)']:.1f}ms | {duration['p(99)']:.1f}ms | {duration['max']:.1f}ms |"
        )


if __name__ == "__main__":
    main()

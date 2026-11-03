# Load Test Results

| | |
|---|---|
| Status | Document engine only |
| Date | 2026-11-01 |
| Scripts | `loadtest/kind/run.sh`, `loadtest/k6/run.sh` |

## Result

**Two document engine pods, each with 1 CPU and 2GiB, hold 100 rps with no errors, at p95 24.4ms and p99 31.5ms.** One pod can't hold it, because it tops out at about 80 rps.

Run 3 pods in practice. Two pods sit at about 67% CPU each, so losing one would overload the other.

## What was tested

These runs measure only the Typst renderer. They don't include the Java client, Kafka, PostgreSQL or storage upload, so end-to-end latency will be higher.

- **Workload:** k6 sent requests at a fixed rate, with different data on every request, using the `subscriptions` template.
- **Check:** every successful response was confirmed to be a PDF.
- **Kubernetes:** one `kind` node, namespace `fp`, Typst 0.15.0, and one worker per pod.
- **Host:** one 12-core machine, also running k6 and the cluster. Results aren't production numbers.

## Fixed pod counts

100 rps for 3 minutes, after a 30 second warm-up. This run and the two below it came before the overload changes.

| Setup | Requests | Achieved rps | Failed | Dropped | p50 | p95 | p99 | Max | CPU per PDF | Cores used | Peak memory per pod |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 pod | 15,387 | 80.0 | 584 | 2,614 | 5,389ms | 11,708ms | 12,072ms | 12,315ms | 12.2ms | 0.98 | 64MiB |
| **2 pods** | **18,001** | **100.0** | **0** | **0** | **15.0ms** | **24.4ms** | **31.5ms** | **68.0ms** | **13.4ms** | **1.34** | **64MiB** |
| 3 pods | 18,000 | 100.0 | 0 | 0 | 14.3ms | 21.1ms | 26.0ms | 74.5ms | 14.1ms | 1.41 | 64MiB |
| 4 pods | 18,001 | 100.0 | 0 | 0 | 14.2ms | 19.9ms | 24.1ms | 44.6ms | 14.4ms | 1.44 | 64MiB |

## Docker baseline

One container with 3 CPUs, 1GiB and 3 workers, at the same 100 rps for 3 minutes.

| Requests | Achieved rps | Failed | Dropped | p50 | p95 | p99 | Max | CPU per PDF | Cores used | Peak memory |
|---|---|---|---|---|---|---|---|---|---|---|
| 18,001 | 100.0 | 0 | 0 | 13.4ms | 16.1ms | 21.4ms | 79.1ms | 14.4ms | 1.44 | 129MiB |

Kubernetes added about 5ms to p95 at 3 pods. CPU per PDF stayed about the same, so most of that gap is likely networking and CPU limits. This wasn't measured separately.

## Autoscaling

The HPA started from 1 pod, at 100 rps for 10 minutes, with a 70% CPU target and a maximum of 6 pods.

| Requests | Achieved rps | Failed | Dropped | p50 | p95 | p99 |
|---|---|---|---|---|---|---|
| 55,496 | 92.5 | 1,048 | 4,505 | 22.9ms | 11,653ms | 12,038ms |

- It settled at 2 pods, with average CPU at 69%.
- The second pod became ready about 5 minutes in. Until then, the single pod was overloaded, which caused all of the failures and most of the slow requests.
- The CPU metric didn't report until about 4.5 minutes in, so the HPA couldn't act before that.
- This delay may be partly specific to this new cluster, because `metrics-server` had only just been installed.

## Gradual test

This run came after the overload changes in the renderer: threads, a render queue limit and a separate path for `/health`. It ran for 5 minutes with no warm-up, using the HPA with a minimum of 2 pods. Each pod had 1 CPU and 2GiB.

| Minute | Target | Requests | Failed | p50 | p95 | p99 | Max | Average CPU |
|---|---|---|---|---|---|---|---|---|
| 1 | 10 rps | 599 | 2 | 17.2ms | 18.0ms | 18.9ms | 23.1ms | About 8% |
| 2 | 20 rps | 1,175 | 0 | 17.3ms | 18.0ms | 18.4ms | 25.2ms | About 18% |
| 3 | 40 rps | 2,350 | 0 | 13.7ms | 15.4ms | 17.1ms | 18.0ms | About 29% |
| 4 | 80 rps | 4,699 | 0 | 12.6ms | 13.9ms | 17.0ms | 35.0ms | About 53% |
| 5 | 80 rps | 4,801 | 0 | 12.8ms | 14.5ms | 17.9ms | 39.9ms | About 53% |

- **Overall:** 13,624 requests, no dropped requests, and 14.0ms of CPU per PDF.
- **Pods:** the HPA stayed at 2 pods, with no failed readiness probes and no restarts. Peak memory was 72MiB per pod.
- **Failures:** 2 requests failed in minute 1, and none failed afterwards. The cause wasn't captured.

## Findings

1. **Use 3 pods for a 100 rps peak.** Two pods are enough for 80 rps, but losing one at 100 rps would overload the other. The local manifest uses `minReplicas: 2`, which matches the gradual test.
2. **Don't rely on the HPA for a sudden burst.** It reacted too slowly in the autoscaling run. In the full design, Kafka holds the backlog during scale-up, but that wasn't tested here.
3. **`/health` no longer shares the render queue.** Automated tests confirm it answers during overload, and extra renders get `503` immediately. A saturated pod hasn't been load tested on Kubernetes since this change.
4. **Memory isn't a limit.** Pods peaked at 64-72MiB, so the 2GiB limit is far more than needed. About 256MiB would be a better starting point.
5. **Capacity is about 75 rps per pod at 1 CPU.** That's based on 13.4ms of CPU per PDF, and it's a rate to stay below, not a target.

## Not tested

- The 500 rps stress ramp and the 10 minute fixed peak. Both were dropped when the Docker run was stopped for this comparison.
- A saturated pod on Kubernetes with the overload changes.
- Multiple nodes, multi-page invoices and other templates.
- The full pipeline with Kafka, PostgreSQL and storage.

## How to run

```bash
GRADUAL=1 loadtest/kind/run.sh
loadtest/kind/run.sh
PEAK_ONLY=1 loadtest/k6/run.sh
```

Both scripts need Docker. `loadtest/kind/run.sh` also needs `kind`, `kubectl` and `helm`, and installs the document engine with its Helm chart in `deploy/helm/document-engine`. They remove their containers, cluster and images when they finish.

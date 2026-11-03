# Load Test Results

| | |
|---|---|
| Status | Document engine and numbering |
| Date | 2026-11-01, numbering on 2026-11-04 |
| Scripts | `loadtest/kind/run.sh`, `loadtest/k6/run.sh`. The numbering harness was removed after its run, see [Numbering](#numbering) |

# Document engine

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

# Numbering

## Result

**One series held 600 rps at p99 4.2ms, and 4 shards held 1,000 rps at p99 2.9ms, with no failed requests and no gaps across 419,145 numbered orders.** One series saturated at about 880 rps, where PostgreSQL CPU rose to 1.87 of its 2 cores and latency jumped to about 1 second.

## What was tested

- **Flow:** k6 posted dummy orders to a temporary load-test endpoint. Each order ran the real numbering path in one transaction: insert the invoice, `NumberingService.assign`, then store the number.
- **Kubernetes:** one `kind` node, namespace `numbering`. 2 app pods with 1 CPU and 1GiB each, and a connection pool of 10 per pod. PostgreSQL 16.4 in one pod with 2 CPUs and 2GiB.
- **Series:** `numbering-test` with 1 shard (`SUB{shard}{fy}{seq:10}`), and `numbering-test-sharded` with 4 shards (`SHD{shard}{fy}{seq:10}`).
- **Checks:** every response was checked against the number format. After each phase, the reconciliation query confirmed every series ran from 1 to N with no gaps or duplicates, and that each counter matched its invoices.
- **Host:** one 12-core machine also running k6, the cluster and another project's containers. Results aren't production numbers.

## Results

Each phase ran for 2 minutes at a fixed arrival rate, after a 30 second warm-up at 20 rps.

| Phase | Requests | Achieved rps | Failed | Dropped | p50 | p95 | p99 | Max | Gaps | PostgreSQL cores | App cores |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 series, 100 rps | 12,001 | 100 | 0 | 0 | 2.5ms | 3.9ms | 5.1ms | 24.5ms | None | 0.11 | 0.29 |
| 1 series, 300 rps | 36,001 | 300 | 0 | 0 | 1.7ms | 2.2ms | 3.7ms | 51.4ms | None | 0.28 | 0.30 |
| 1 series, 600 rps | 72,000 | 600 | 0 | 0 | 1.4ms | 1.9ms | 4.2ms | 69.0ms | None | 0.44 | 0.34 |
| 1 series, 1,000 rps | 106,540 | 880 | 0 | 13,461 | 1,075ms | 1,399ms | 1,662ms | 3,404ms | None | 1.87 | 0.75 |
| 4 shards, 600 rps | 72,001 | 600 | 0 | 0 | 1.4ms | 1.8ms | 3.2ms | 26.7ms | None | 0.43 | 0.31 |
| 4 shards, 1,000 rps | 120,001 | 1,000 | 0 | 0 | 1.3ms | 1.7ms | 2.9ms | 30.5ms | None | 0.67 | 0.45 |

"Dropped" means k6 couldn't start a request on time because earlier ones were still waiting, so the load generator was holding requests back.

At 1,000 rps, the 4 shards split the load evenly: 30,007, 30,003, 29,994 and 29,997 numbers.

## Findings

1. **The row lock is the limit, and shards remove it.** At 1,000 rps, one series made PostgreSQL use 1.87 cores, while 4 shards at the same rate used 0.67. The extra CPU was spent on workers queuing for one counter row, not on useful work.
2. **One series covers the 10-year plan.** It ran 600 rps at p99 4.2ms, about 5 times the year-10 average of 116 rps.
3. **Add shards before a series' peak reaches about 600 rps.** Above that, one series degrades sharply instead of gradually.
4. **Latency is far inside the targets.** Below saturation, p99 stayed under 5ms against the 150ms goal.
5. **Correctness held under overload.** Even the saturated phase numbered every order exactly once, with no gaps.

## Not tested

- More than 2 app pods, or pool sizes other than 10. More concurrent connections per series would likely lower the single-series limit, as the earlier `pgbench` run showed.
- A managed database with network latency, which lengthens each lock hold.
- Long runs, PostgreSQL restarts, or failover under load. The failure tests cover these at small scale.
- The full flow through Kafka, rendering and storage, which waits for the invoice module.

## How to reproduce

The harness was temporary and has been removed. It is in commit `ed7ddf9`:

```bash
git checkout ed7ddf9
loadtest/numbering/run.sh
```

It needs Docker, `kind` and `kubectl`, and deletes its cluster and images when it finishes.


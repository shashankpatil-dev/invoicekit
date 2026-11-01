#!/bin/sh
# Finds how many 1 CPU, 2GiB document engine pods it takes to hold 100 rps on Kubernetes.
#
# Run it from the repo root: loadtest/k6/run-kind.sh
# It needs Docker, kind and kubectl, and takes about 35 minutes. Raw results go to loadtest/results/kind.
#
# It tests fixed pod counts first, then lets the autoscaler choose, starting from one pod.
# Set POD_COUNTS="1 2" to choose the pod counts, and SKIP_AUTOSCALE=1 to skip the autoscaler run.
# Set GRADUAL=1 to run only the 5 minute gradual test: 10, 20, 40, then 80 rps, with at least 2 pods.
# The cluster and test images are always removed at the end, even after a failure.
set -eu

CLUSTER=fp
NAMESPACE=fp
RENDERER_IMAGE=invoicekit/typst-renderer:loadtest
K6_IMAGE=grafana/k6:0.57.0
METRICS_SERVER=https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.8.0/components.yaml
RESULTS=loadtest/results/kind

TARGET_RATE=100
WARMUP_RATE=20
FIXED_DURATION=3m
AUTOSCALE_DURATION=10m
POD_COUNTS=${POD_COUNTS:-1 2 3 4}
GRADUAL_STAGES=1m:10,5s:20,55s:20,5s:40,55s:40,5s:80,55s:80,1m:80

cleanup() {
  if [ -n "${TIMELINE_PID:-}" ]; then
    kill "$TIMELINE_PID" 2>/dev/null || true
  fi
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  docker rmi "$RENDERER_IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kube() {
  kubectl --namespace "$NAMESPACE" "$@"
}

renderer_pods() {
  kube get pods -l app=document-engine -o name
}

# Adds up CPU time used by all renderer pods, in microseconds.
total_cpu_usec() {
  total=0
  for pod in $(renderer_pods); do
    usage=$(kube exec "$pod" -- sh -c 'grep usage_usec /sys/fs/cgroup/cpu.stat' | cut -d' ' -f2)
    total=$((total + usage))
  done
  echo "$total"
}

# Finds the highest memory peak of any renderer pod, in bytes.
highest_memory_peak() {
  highest=0
  for pod in $(renderer_pods); do
    peak=$(kube exec "$pod" -- cat /sys/fs/cgroup/memory.peak)
    if [ "$peak" -gt "$highest" ]; then
      highest=$peak
    fi
  done
  echo "$highest"
}

# Starts k6 as a Job and waits for it. Arguments: phase name, rate, duration.
run_job() {
  sed -e "s/__PHASE__/$1/g" -e "s/__RATE__/$2/g" -e "s/__DURATION__/$3/g" -e "s/__STAGES__/${STAGES:-}/g" deploy/kind/k6-job.yaml |
    kubectl apply -f - >/dev/null
  kube wait --for=condition=complete --timeout=20m "job/k6-$1" >/dev/null
}

# Runs one measured phase and saves its results. Arguments: phase name, rate, duration.
measure() {
  cpu_before=$(total_cpu_usec)
  run_job "$1" "$2" "$3"
  kube logs "job/k6-$1" | tail -n 1 >"$RESULTS/$1.json"
  echo "$1 $(($(total_cpu_usec) - cpu_before)) $(highest_memory_peak)" >>"$RESULTS/resources.txt"
  kube delete job "k6-$1" >/dev/null
  echo "$1 failedProbes=$(kube get events --field-selector reason=Unhealthy -o name | wc -l) restarts=$(kube get pods -l app=document-engine -o jsonpath='{.items[*].status.containerStatuses[0].restartCount}')" >>"$RESULTS/pod-health.txt"
}

# Warms the current pods without recording results, like pods that have been serving for a while.
warm_up() {
  run_job "warmup-$1" "$WARMUP_RATE" 30s
  kube delete job "k6-warmup-$1" >/dev/null
}

# Records the pod count and CPU use every 15 seconds while the autoscaler runs.
record_timeline() {
  started=$(date +%s)
  while true; do
    replicas=$(kube get hpa document-engine -o jsonpath='{.status.currentReplicas}')
    ready=$(kube get deployment document-engine -o jsonpath='{.status.readyReplicas}')
    cpu=$(kube get hpa document-engine -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}')
    echo "$(($(date +%s) - started))s pods=$replicas ready=$ready averageCpu=${cpu:-unknown}%" >>"$RESULTS/autoscale-timeline.txt"
    sleep 15
  done
}

rm -rf "$RESULTS"
mkdir -p "$RESULTS"

kind create cluster --name "$CLUSTER" --config deploy/kind/kind-config.yaml --wait 120s

# The autoscaler reads CPU from metrics-server. Local kubelets use self-signed certificates.
kubectl apply -f "$METRICS_SERVER" >/dev/null
kubectl --namespace kube-system patch deployment metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' >/dev/null
kubectl --namespace kube-system rollout status deployment metrics-server --timeout=120s >/dev/null

docker build -q -t "$RENDERER_IMAGE" -f services/typst-renderer/Dockerfile . >/dev/null
docker pull -q "$K6_IMAGE" >/dev/null
kind load docker-image --name "$CLUSTER" "$RENDERER_IMAGE" "$K6_IMAGE"

kubectl apply -f deploy/kind/document-engine.yaml >/dev/null
kube create configmap k6-scripts --from-file=loadtest/k6/render.js >/dev/null
kube create configmap k6-payloads --from-file=src/test/resources/payloads/subscriptions-domestic.json >/dev/null

if [ "${GRADUAL:-}" = "1" ]; then
  kube scale deployment document-engine --replicas 2 >/dev/null
  kube rollout status deployment document-engine --timeout=120s >/dev/null
  kubectl apply -f deploy/kind/document-engine-hpa.yaml >/dev/null
  record_timeline &
  TIMELINE_PID=$!
  STAGES=$GRADUAL_STAGES
  measure gradual 10 5m
  python3 -I loadtest/k6/summarize.py "$RESULTS"
  python3 -I loadtest/k6/summarize_minutes.py "$RESULTS/gradual.json"
  cat "$RESULTS/pod-health.txt" "$RESULTS/autoscale-timeline.txt"
  exit 0
fi

for pods in $POD_COUNTS; do
  kube scale deployment document-engine --replicas "$pods" >/dev/null
  kube rollout status deployment document-engine --timeout=120s >/dev/null
  warm_up "$pods"
  measure "pods-$pods" "$TARGET_RATE" "$FIXED_DURATION"
done

if [ "${SKIP_AUTOSCALE:-}" = "1" ]; then
  python3 -I loadtest/k6/summarize.py "$RESULTS"
  cat "$RESULTS/pod-health.txt"
  exit 0
fi

kube delete deployment document-engine >/dev/null
kube wait --for=delete pod -l app=document-engine --timeout=120s >/dev/null 2>&1 || true
kubectl apply -f deploy/kind/document-engine.yaml >/dev/null
kube rollout status deployment document-engine --timeout=120s >/dev/null
warm_up autoscale

kubectl apply -f deploy/kind/document-engine-hpa.yaml >/dev/null
record_timeline &
TIMELINE_PID=$!

measure autoscale "$TARGET_RATE" "$AUTOSCALE_DURATION"

python3 -I loadtest/k6/summarize.py "$RESULTS"

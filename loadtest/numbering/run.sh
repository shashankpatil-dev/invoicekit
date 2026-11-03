#!/bin/sh
# TEMPORARY: measures invoice numbering on Kubernetes, through the loadtest profile's dummy orders.
#
# Run it from the repo root: loadtest/numbering/run.sh
# It needs Docker, kind and kubectl, and the default plan takes about 15 minutes.
# Raw results go to loadtest/results/numbering.
#
# PHASES lists the phases, separated by spaces, each as name:businessUnit:rps:duration:appPods:poolSize.
# SMOKE=1 runs only a 30 second, 50 rps check on the 1-shard series.
# STAGES=30s:100,1m:300 ramps every phase through those steps, starting from the phase's rate.
# Orders are deleted before each phase, so every phase starts its series at 1.
# The cluster and the app and k6 images are always removed at the end, even after a failure.
set -eu

CLUSTER=numbering
NAMESPACE=numbering
HERE=loadtest/numbering
APP_IMAGE=invoicekit/numbering-loadtest:loadtest
POSTGRES_IMAGE=postgres:16.4-alpine
K6_IMAGE=grafana/k6:0.57.0
METRICS_SERVER=https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.8.0/components.yaml
RESULTS=loadtest/results/numbering
APP_PROXY=/api/v1/namespaces/$NAMESPACE/services/numbering:8081/proxy/loadtest

DEFAULT_PHASES="warmup:numbering-test:20:30s:2:10
one-shard-100:numbering-test:100:2m:2:10
one-shard-300:numbering-test:300:2m:2:10
one-shard-600:numbering-test:600:2m:2:10
four-shards-600:numbering-test-sharded:600:2m:2:10"
if [ "${SMOKE:-}" = "1" ]; then
  PHASES="smoke:numbering-test:50:30s:2:10"
fi
PHASES=${PHASES:-$DEFAULT_PHASES}

# The postgres image stays, because the tests' Testcontainers use it too.
cleanup() {
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  docker rmi "$APP_IMAGE" "$K6_IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

kube() {
  kubectl --namespace "$NAMESPACE" "$@"
}

# Lists the pods with the given app label, skipping any still shutting down after a rollout.
pods() {
  kube get pods -l "app=$1" \
    -o go-template='{{range .items}}{{if not .metadata.deletionTimestamp}}{{.metadata.name}} {{end}}{{end}}'
}

# Adds up CPU time used by the pods with the given app label, in microseconds.
cpu_usec() {
  total=0
  for pod in $(pods "$1"); do
    usage=$(kube exec "$pod" -- grep usage_usec /sys/fs/cgroup/cpu.stat | cut -d' ' -f2)
    total=$((total + usage))
  done
  echo "$total"
}

# Finds the highest memory peak of any pod with the given app label, in bytes.
memory_peak() {
  highest=0
  for pod in $(pods "$1"); do
    peak=$(kube exec "$pod" -- cat /sys/fs/cgroup/memory.peak)
    if [ "$peak" -gt "$highest" ]; then
      highest=$peak
    fi
  done
  echo "$highest"
}

# Sets the app pod count and connection pool size, and waits for the pods to be ready.
configure_app() {
  kube set env deployment/numbering HIKARI_POOL_SIZE="$2" >/dev/null
  kube scale deployment/numbering --replicas "$1" >/dev/null
  kube rollout status deployment/numbering --timeout=300s >/dev/null
}

# Runs one phase and saves its results. Arguments: name, business unit, rate, duration, app pods, pool size.
run_phase() {
  echo "Phase $1: $2 at $3 rps for $4, $5 app pods, pool size $6"
  configure_app "$5" "$6"
  kubectl delete --raw "$APP_PROXY/orders" >/dev/null
  postgres_cpu=$(cpu_usec postgres)
  app_cpu=$(cpu_usec numbering)
  sed -e "s/__PHASE__/$1/g" -e "s/__BUSINESS_UNIT__/$2/g" -e "s/__RATE__/$3/g" -e "s/__DURATION__/$4/g" \
    -e "s/__STAGES__/${STAGES:-}/g" "$HERE/k8s/k6-job.yaml" | kubectl apply -f - >/dev/null
  kube wait --for=condition=complete --timeout=20m "job/k6-$1" >/dev/null
  kube logs "job/k6-$1" | tail -n 1 >"$RESULTS/$1.json"
  echo "$1 $(($(cpu_usec postgres) - postgres_cpu)) $(memory_peak postgres) $(($(cpu_usec numbering) - app_cpu)) $(memory_peak numbering)" \
    >>"$RESULTS/resources.txt"
  kubectl get --raw "$APP_PROXY/reconciliation" >"$RESULTS/$1-reconciliation.json"
  kube delete job "k6-$1" >/dev/null
}

rm -rf "$RESULTS"
mkdir -p "$RESULTS"

./gradlew --quiet loadtestBootJar
docker build -q -t "$APP_IMAGE" -f "$HERE/Dockerfile" build/libs >/dev/null
docker pull -q "$POSTGRES_IMAGE" >/dev/null
docker pull -q "$K6_IMAGE" >/dev/null

kind create cluster --name "$CLUSTER" --config loadtest/kind/kind-config.yaml --wait 120s

# metrics-server gives kubectl top for watching pods during a run. Local kubelets use self-signed certificates.
kubectl apply -f "$METRICS_SERVER" >/dev/null
kubectl --namespace kube-system patch deployment metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' >/dev/null
kubectl --namespace kube-system rollout status deployment metrics-server --timeout=120s >/dev/null

kind load docker-image --name "$CLUSTER" "$APP_IMAGE" "$POSTGRES_IMAGE" "$K6_IMAGE"

kubectl create namespace "$NAMESPACE" >/dev/null
kubectl apply -f "$HERE/k8s/postgres.yaml" >/dev/null
kube rollout status deployment/postgres --timeout=180s >/dev/null
kubectl apply -f "$HERE/k8s/app.yaml" >/dev/null
kube rollout status deployment/numbering --timeout=300s >/dev/null
kube create configmap k6-numbering --from-file="$HERE/numbering.js" >/dev/null

for phase in $PHASES; do
  # Splits name:businessUnit:rps:duration:appPods:poolSize into run_phase's arguments.
  # shellcheck disable=SC2046
  run_phase $(echo "$phase" | tr ':' ' ')
done

python3 -I "$HERE/summarize.py" "$RESULTS"

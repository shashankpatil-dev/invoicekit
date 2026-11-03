#!/bin/sh
# Runs the Typst renderer load test and prints a results table.
#
# Run it from the repo root: loadtest/k6/run.sh
# It needs Docker, and takes about 19 minutes. Raw results go to loadtest/results/docker.
#
# Set PEAK_ONLY=1 to run just 3 minutes at 100 rps, which matches the Kubernetes pod runs.
#
# Containers, the network and the test image are always removed at the end, even after a failure.
set -eu

K6_IMAGE=grafana/k6:0.57.0
RENDERER_IMAGE=invoicekit-typst-renderer-loadtest
RENDERER=invoicekit-typst-loadtest
NETWORK=invoicekit-loadtest
RESULTS=${RESULTS:-loadtest/results/docker}

# The renderer gets a fixed budget, so results can be compared between runs.
CPU_LIMIT=${CPU_LIMIT:-3}
MEMORY_LIMIT=${MEMORY_LIMIT:-1g}

cleanup() {
  docker rm -f "$RENDERER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  docker rmi "$RENDERER_IMAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Reads total CPU time used by the renderer, in microseconds.
cpu_usage_usec() {
  docker exec "$RENDERER" sh -c 'grep usage_usec /sys/fs/cgroup/cpu.stat' | cut -d' ' -f2
}

# Runs one phase. Arguments: phase name, rate, duration, optional end rate.
run_phase() {
  phase=$1
  cpu_before=$(cpu_usage_usec)

  docker run --rm --network "$NETWORK" --user "$(id -u)" \
    -e RENDERER_URL="http://$RENDERER:3000" \
    -e PHASE="$phase" -e RATE="$2" -e DURATION="$3" -e END_RATE="${4:-}" \
    -v "$PWD/loadtest/k6:/scripts:ro" \
    -v "$PWD/src/test/resources/payloads:/payloads:ro" \
    -v "$PWD/$RESULTS:/results" \
    "$K6_IMAGE" run --quiet --no-usage-report /scripts/render.js

  cpu_after=$(cpu_usage_usec)
  memory_peak=$(docker exec "$RENDERER" cat /sys/fs/cgroup/memory.peak)
  echo "$phase $((cpu_after - cpu_before)) $memory_peak" >>"$RESULTS/resources.txt"
}

rm -rf "$RESULTS"
mkdir -p "$RESULTS"

docker build -q -t "$RENDERER_IMAGE" -f document-engine/Dockerfile . >/dev/null
docker network create "$NETWORK" >/dev/null
docker run -d --name "$RENDERER" --network "$NETWORK" \
  --cpus "$CPU_LIMIT" --memory "$MEMORY_LIMIT" \
  -e WEB_CONCURRENCY="$CPU_LIMIT" "$RENDERER_IMAGE" >/dev/null

until docker exec "$RENDERER" python -c "import urllib.request; urllib.request.urlopen('http://localhost:3000/health')" 2>/dev/null; do
  sleep 1
done

run_phase warmup 20 30s
if [ "${PEAK_ONLY:-}" = "1" ]; then
  run_phase peak 100 3m
else
  run_phase sustained 20 5m
  run_phase peak 100 10m
  run_phase stress 100 3m 500
fi

python3 -I loadtest/k6/summarize.py "$RESULTS"

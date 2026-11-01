// Load test for the Typst renderer.
//
// Requests arrive at a fixed rate whether or not earlier ones have finished, like Kafka delivering
// a backlog. A slow renderer therefore shows up as higher latency instead of hiding behind fewer
// requests.
//
// Settings come from environment variables:
//   PHASE      name used for the results file
//   RATE       requests per second, or the starting rate when END_RATE is set
//   DURATION   how long to run, such as 5m
//   END_RATE   optional. Ramps from RATE to this rate over DURATION
//   STAGES     optional. Steps such as 1m:10,5s:20, as duration:rate. Results are also split by minute
//   SUMMARY_TO_STDOUT   optional. Prints results instead of writing a file, for Kubernetes Jobs
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { Counter, Rate, Trend } from "k6/metrics";

const RENDERER_URL = __ENV.RENDERER_URL;
const PHASE = __ENV.PHASE;
const RATE = Number(__ENV.RATE);
const END_RATE = Number(__ENV.END_RATE || 0);
const DURATION = __ENV.DURATION;

const STAGES = __ENV.STAGES || "";
const MINUTES = 5;

const SAMPLE_PAYLOAD = open("/payloads/subscriptions-domestic.json");
const REQUEST_OPTIONS = {
  headers: { "Content-Type": "application/json" },
  responseType: "binary",
  timeout: "30s",
};
const PDF_HEADER = "%PDF";

const fixedRate = {
  executor: "constant-arrival-rate",
  rate: RATE,
  timeUnit: "1s",
  duration: DURATION,
  preAllocatedVUs: 100,
  maxVUs: 1000,
};

const rampingRate = {
  executor: "ramping-arrival-rate",
  startRate: RATE,
  timeUnit: "1s",
  stages: [{ target: END_RATE, duration: DURATION }],
  preAllocatedVUs: 200,
  maxVUs: 1000,
};

const stagedRate = {
  executor: "ramping-arrival-rate",
  startRate: RATE,
  timeUnit: "1s",
  stages: STAGES.split(",").map((stage) => {
    const [duration, target] = stage.split(":");
    return { duration, target: Number(target) };
  }),
  preAllocatedVUs: 100,
  maxVUs: 1000,
};

const minuteMetrics = Array.from({ length: MINUTES }, (_, index) => ({
  duration: new Trend(`minute_${index + 1}_duration`, true),
  failed: new Rate(`minute_${index + 1}_failed`),
  requests: new Counter(`minute_${index + 1}_requests`),
}));

function chooseScenario() {
  if (STAGES) return stagedRate;
  return END_RATE ? rampingRate : fixedRate;
}

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ["avg", "med", "p(95)", "p(99)", "max"],
  scenarios: { render: chooseScenario() },
};

// Typst caches repeated work, so every request needs different data or the results look too good.
function uniquePayload() {
  const requestId = exec.scenario.iterationInTest;
  const invoiceTotal = 10000 + (requestId % 80000);
  return SAMPLE_PAYLOAD.replaceAll("SUBMH2627000142", `SUB${String(requestId).padStart(12, "0")}`)
    .replaceAll("Example Traders Private Limited", `Customer ${requestId} Private Limited`)
    .replaceAll("Growth plan", `Growth plan ${requestId}`)
    .replaceAll("17110.00", `${invoiceTotal}.00`);
}

function isPdf(response) {
  const header = new Uint8Array(response.body.slice(0, PDF_HEADER.length));
  return String.fromCharCode(...header) === PDF_HEADER;
}

export default function () {
  const response = http.post(`${RENDERER_URL}/render/subscriptions`, uniquePayload(), REQUEST_OPTIONS);
  check(response, {
    "status is 200": (result) => result.status === 200,
    "body is a PDF": (result) => result.status === 200 && isPdf(result),
  });
  if (STAGES) recordMinute(response);
}

function recordMinute(response) {
  const elapsedMinutes = Math.floor(exec.instance.currentTestRunDuration / 60000);
  const metrics = minuteMetrics[Math.min(elapsedMinutes, MINUTES - 1)];
  metrics.duration.add(response.timings.duration);
  metrics.failed.add(response.status !== 200);
  metrics.requests.add(1);
}

export function handleSummary(data) {
  const summary = JSON.stringify(data) + "\n";
  return __ENV.SUMMARY_TO_STDOUT ? { stdout: summary } : { [`/results/${PHASE}.json`]: summary };
}

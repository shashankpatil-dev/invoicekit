// TEMPORARY load test for invoice numbering.
//
// Each request places one dummy order, which inserts an invoice and takes the next number in its
// series in one transaction. Requests arrive at a fixed rate whether or not earlier ones have
// finished, like Kafka delivering a backlog, so a busy counter shows up as higher latency.
//
// Settings come from environment variables:
//   APP_URL        where the loadtest profile app listens, such as http://numbering:8081
//   PHASE          name used for results, and the prefix of every order's event ID
//   BUSINESS_UNIT  numbering-test (1 shard) or numbering-test-sharded (4 shards)
//   RATE           orders per second, or the starting rate when STAGES is set
//   DURATION       how long to run, such as 2m
//   STAGES         optional. Steps such as 30s:100,1m:300, as duration:rate
//   INVOICE_DATE   optional. Defaults to 2026-11-04, in financial year 2026-27
//   SUMMARY_TO_STDOUT   optional. Prints results instead of writing a file, for Kubernetes Jobs
import http from "k6/http";
import { check } from "k6";
import exec from "k6/execution";
import { Counter } from "k6/metrics";

const APP_URL = __ENV.APP_URL;
const PHASE = __ENV.PHASE;
const BUSINESS_UNIT = __ENV.BUSINESS_UNIT;
const RATE = Number(__ENV.RATE);
const DURATION = __ENV.DURATION;
const STAGES = __ENV.STAGES || "";
const INVOICE_DATE = __ENV.INVOICE_DATE || "2026-11-04";

const REQUEST_OPTIONS = { headers: { "Content-Type": "application/json" }, timeout: "30s" };
const INVOICE_NUMBER = /^(SUB|SHD)[A-Z][0-9]{2}[0-9]{10}$/;

// A 503 means the series stayed locked too long, which the real flow would retry.
const busyResponses = new Counter("busy_responses");

const fixedRate = {
  executor: "constant-arrival-rate",
  rate: RATE,
  timeUnit: "1s",
  duration: DURATION,
  preAllocatedVUs: 100,
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

export const options = {
  summaryTrendStats: ["avg", "med", "p(95)", "p(99)", "max"],
  scenarios: { orders: STAGES ? stagedRate : fixedRate },
};

export default function () {
  // Every order has its own event, so none is a repeat that skips the counter.
  const order = {
    businessUnit: BUSINESS_UNIT,
    sourceEventId: `${PHASE}-${exec.scenario.iterationInTest}`,
    invoiceDate: INVOICE_DATE,
  };
  const response = http.post(`${APP_URL}/loadtest/orders`, JSON.stringify(order), REQUEST_OPTIONS);
  if (response.status === 503) busyResponses.add(1);
  check(response, {
    "status is 200": (result) => result.status === 200,
    "number is well formed": (result) =>
      result.status === 200 && INVOICE_NUMBER.test(result.json("number")),
  });
}

export function handleSummary(data) {
  const summary = JSON.stringify(data) + "\n";
  return __ENV.SUMMARY_TO_STDOUT ? { stdout: summary } : { [`/results/${PHASE}.json`]: summary };
}

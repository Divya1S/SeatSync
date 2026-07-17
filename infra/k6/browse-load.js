// Read-path load test: event browse + detail + seat map.
// Used to measure the effect of the catalog Redis cache-aside layer.
//
// Run (host networking so localhost:8080 is the gateway):
//   docker run --rm -i --network host grafana/k6 run - < infra/k6/browse-load.js
// or with a local k6 install:
//   k6 run infra/k6/browse-load.js
//
// Compare cold vs warm cache:
//   docker compose exec redis redis-cli FLUSHDB   # cold run baseline
//   (run once to warm, then run again for the "with cache" numbers)

import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const VUS = Number(__ENV.VUS || 30);
const QUERY = __ENV.QUERY || ""; // narrow the tested events, e.g. QUERY=Mega

const detailLatency = new Trend("event_detail_latency", true);
const seatmapLatency = new Trend("seatmap_latency", true);

export const options = {
  stages: [
    { duration: "20s", target: VUS },
    { duration: "60s", target: VUS },
    { duration: "10s", target: 0 },
  ],
  thresholds: {
    http_req_failed: ["rate<0.01"],
    event_detail_latency: ["p(95)<500"],
  },
};

export function setup() {
  const res = http.get(`${BASE}/api/catalog/events?size=20&q=${QUERY}`);
  check(res, { "search ok": (r) => r.status === 200 });
  const ids = JSON.parse(res.body).content.map((e) => e.id);
  if (ids.length === 0) throw new Error("No published events — seed data missing?");
  return { ids };
}

export default function (data) {
  const list = http.get(`${BASE}/api/catalog/events?size=20&q=${QUERY}`);
  check(list, { "list 200": (r) => r.status === 200 });

  const id = data.ids[Math.floor(Math.random() * data.ids.length)];

  const detail = http.get(`${BASE}/api/catalog/events/${id}`);
  check(detail, { "detail 200": (r) => r.status === 200 });
  detailLatency.add(detail.timings.duration);

  const seatmap = http.get(`${BASE}/api/catalog/events/${id}/seatmap`);
  check(seatmap, { "seatmap 200": (r) => r.status === 200 });
  seatmapLatency.add(seatmap.timings.duration);

  sleep(0.3 + Math.random() * 0.4);
}

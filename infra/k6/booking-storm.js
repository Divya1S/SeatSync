// Write-path stress test: many users race to hold + confirm the seats of a
// single event. The invariant under test mirrors the Testcontainers race IT:
// confirmed bookings must NEVER exceed the seat count, and contested seats
// must fail cleanly (409), never with 5xx.
//
//   docker run --rm -i --network host grafana/k6 run \
//     -e EVENT_ID=<uuid> - < infra/k6/booking-storm.js
//
// EVENT_ID defaults to the first published event found via search.

import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const USERS = Number(__ENV.USERS || 50);

const booked = new Counter("bookings_confirmed");
const conflicts = new Counter("seat_conflicts_409");
const serverErrors = new Counter("server_errors_5xx");

export const options = {
  scenarios: {
    storm: {
      executor: "per-vu-iterations",
      vus: USERS,
      iterations: 3,
      maxDuration: "2m",
    },
  },
  thresholds: {
    server_errors_5xx: ["count==0"],
    http_req_failed: ["rate<0.5"], // 409s are EXPECTED under contention
  },
};

export function setup() {
  let eventId = __ENV.EVENT_ID;
  if (!eventId) {
    const res = http.get(`${BASE}/api/catalog/events?size=1`);
    eventId = JSON.parse(res.body).content[0].id;
  }

  // Seat identities come from the catalog seat map (same as the frontend);
  // the booking live-seats view only materializes once inventory is seeded
  // by the first hold.
  const smRes = http.get(`${BASE}/api/catalog/events/${eventId}/seatmap`);
  const allSeatIds = JSON.parse(smRes.body).sections.flatMap((sec) =>
    sec.rows.flatMap((r) => r.seats.map((s) => s.seatId))
  );

  // Register one account per VU (idempotent-ish: 409 on rerun is fine, then login).
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const email = `k6-user-${i}@seatsync.local`;
    http.post(
      `${BASE}/api/auth/register`,
      JSON.stringify({ email, password: "k6-password-1!", fullName: `K6 User ${i}`, role: "ATTENDEE" }),
      { headers: { "Content-Type": "application/json" } }
    );
    const login = http.post(
      `${BASE}/api/auth/login`,
      JSON.stringify({ email, password: "k6-password-1!" }),
      { headers: { "Content-Type": "application/json" } }
    );
    tokens.push(JSON.parse(login.body).accessToken);
  }
  return { eventId, tokens, allSeatIds };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  const auth = { headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` } };

  // Overlay live state (may be empty before the first hold seeds inventory).
  const seatsRes = http.get(`${BASE}/api/events/${data.eventId}/seats`);
  const taken = new Set(
    seatsRes.status === 200
      ? JSON.parse(seatsRes.body).seats.filter((s) => s.status !== "AVAILABLE").map((s) => s.seatId)
      : []
  );
  const available = data.allSeatIds.filter((id) => !taken.has(id));
  if (available.length === 0) return;

  const seatId = available[Math.floor(Math.random() * available.length)];

  const hold = http.post(
    `${BASE}/api/holds`,
    JSON.stringify({ eventId: data.eventId, seatId }),
    auth
  );
  if (hold.status === 409) { conflicts.add(1); return; }
  if (hold.status >= 500) { serverErrors.add(1); return; }
  check(hold, { "hold 201": (r) => r.status === 201 });
  if (hold.status !== 201) return;

  const confirm = http.post(
    `${BASE}/api/bookings`,
    JSON.stringify({ holdId: JSON.parse(hold.body).holdId }),
    auth
  );
  if (confirm.status === 201) booked.add(1);
  else if (confirm.status === 409) conflicts.add(1);
  else if (confirm.status >= 500) serverErrors.add(1);
}

export function teardown(data) {
  const seatsRes = http.get(`${BASE}/api/events/${data.eventId}/seats`);
  const seats = JSON.parse(seatsRes.body).seats;
  const bookedCount = seats.filter((s) => s.status === "BOOKED").length;
  console.log(
    `Final state: ${bookedCount} booked of ${data.allSeatIds.length} seats — oversell = ${
      bookedCount > data.allSeatIds.length ? "YES (BUG!)" : "no"
    }`
  );
}

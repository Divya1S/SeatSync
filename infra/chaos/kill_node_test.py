#!/usr/bin/env python3
"""Kill-the-node chaos test: SIGKILL one of two booking-service replicas
mid-transaction under load, then assert the correctness invariants.

Prereq:
  docker compose -f docker-compose.yml -f infra/chaos/docker-compose.chaos.yml \
      up -d --build --scale booking-service=2
  (wait for health; the chaos overlay sets HOLD_TTL=PT20S and unbinds :8083)

Asserts (correctness — must ALWAYS hold):
  A. no seat has more than one CONFIRMED booking
  B. confirmed bookings never exceed seat capacity
  C. every HELD hold expires: no HELD row remains past TTL + sweep grace
     (orphans created by the killed replica are swept by the survivor)
  D. no outbox row stays unpublished (the survivor's relay picks up rows
     the dead replica claimed — SKIP LOCKED locks die with its connections)
Availability MAY degrade during failover (5xx while the gateway's connection
pool drains to the dead IP) — recorded and reported, not asserted.
"""
import concurrent.futures
import json
import random
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

GW = "http://localhost:8080"
USERS = 12
STORM_SECONDS = 30
KILL_AT = 6
TTL_SECONDS = 20          # matches HOLD_TTL in the chaos overlay
SWEEP_GRACE = 25          # sweeper cadence 10s + margin

counts, counts_lock = {}, threading.Lock()


def bump(key):
    with counts_lock:
        counts[key] = counts.get(key, 0) + 1


def req(method, path, body=None, token=None, timeout=10):
    r = urllib.request.Request(GW + path, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(r, data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"null")
        except Exception:
            return e.code, None
    except Exception:
        return 599, None  # connection error / timeout during failover


def sql(db, query):
    out = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres",
         "psql", "-U", "seatsync", "-d", db, "-tA", "-c", query],
        capture_output=True, text=True, check=True)
    return out.stdout.strip()


def storm_worker(token, event_id, seat_ids, deadline):
    while time.time() < deadline:
        seat = random.choice(seat_ids)
        s, hold = req("POST", "/api/holds", {"eventId": event_id, "seatId": seat}, token, timeout=8)
        bump(f"hold_{s // 100}xx")
        if s == 201:
            s2, _ = req("POST", "/api/bookings", {"holdId": hold["holdId"]}, token, timeout=8)
            bump(f"confirm_{s2 // 100}xx")
        time.sleep(random.uniform(0.02, 0.1))


def main():
    # -- setup: users + target event ------------------------------------
    print("== setup")
    tokens = []
    for i in range(USERS):
        email = f"chaos-{i}@seatsync.local"
        req("POST", "/api/auth/register",
            {"email": email, "password": "chaos-pass-1!", "fullName": f"Chaos {i}", "role": "ATTENDEE"})
        s, login = req("POST", "/api/auth/login", {"email": email, "password": "chaos-pass-1!"})
        assert s == 200, f"login failed: {s}"
        tokens.append(login["accessToken"])

    s, page = req("GET", "/api/catalog/events?category=CAMPUS&size=1")
    event = page["content"][0]
    event_id = event["id"]
    s, sm = req("GET", f"/api/catalog/events/{event_id}/seatmap")
    seat_ids = [x["seatId"] for sec in sm["sections"] for r in sec["rows"] for x in r["seats"]]
    capacity = len(seat_ids)
    print(f"   event {event['name']} — {capacity} seats, {USERS} users, "
          f"{STORM_SECONDS}s storm, SIGKILL at t+{KILL_AT}s")

    replicas = subprocess.run(["docker", "compose", "ps", "-q", "booking-service"],
                              capture_output=True, text=True, check=True).stdout.split()
    assert len(replicas) >= 2, f"need 2 booking replicas, found {len(replicas)} — use the chaos overlay + --scale"

    # -- storm + kill -----------------------------------------------------
    print("== storm")
    deadline = time.time() + STORM_SECONDS
    with concurrent.futures.ThreadPoolExecutor(max_workers=USERS) as pool:
        futures = [pool.submit(storm_worker, t, event_id, seat_ids, deadline) for t in tokens]
        time.sleep(KILL_AT)
        victim = random.choice(replicas)
        print(f"   SIGKILL {victim[:12]} at t+{KILL_AT}s (mid-load)")
        subprocess.run(["docker", "kill", "-s", "SIGKILL", victim], check=True, capture_output=True)
        concurrent.futures.wait(futures)
    print(f"   request outcomes: {dict(sorted(counts.items()))}")

    # -- settle: TTL + sweep ---------------------------------------------
    print(f"== waiting TTL({TTL_SECONDS}s) + sweep grace({SWEEP_GRACE}s) for orphan cleanup")
    time.sleep(TTL_SECONDS + SWEEP_GRACE)

    # -- invariants -------------------------------------------------------
    failures = []

    dupes = sql("seatsync_booking",
                f"SELECT seat_id, count(*) FROM bookings WHERE event_id='{event_id}' "
                "AND status='CONFIRMED' GROUP BY seat_id HAVING count(*) > 1;")
    if dupes:
        failures.append(f"A: double-confirmed seats!\n{dupes}")

    booked = int(sql("seatsync_booking",
                     f"SELECT count(*) FROM bookings WHERE event_id='{event_id}' AND status='CONFIRMED';"))
    if booked > capacity:
        failures.append(f"B: oversell! {booked} > capacity {capacity}")

    orphans = sql("seatsync_booking",
                  f"SELECT count(*) FROM holds WHERE event_id='{event_id}' AND status='HELD' "
                  f"AND expires_at < now() - interval '5 seconds';")
    if int(orphans) != 0:
        failures.append(f"C: {orphans} orphaned HELD holds survived past TTL+sweep")

    stuck = sql("seatsync_booking",
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL "
                "AND created_at < now() - interval '60 seconds';")
    if int(stuck) != 0:
        failures.append(f"D: {stuck} outbox rows stuck unpublished > 60s")

    s, _ = req("GET", f"/api/events/{event_id}/seats")
    recovered = s == 200

    print("== results")
    print(f"   A no double-booking: {'PASS' if not any(f.startswith('A') for f in failures) else 'FAIL'}")
    print(f"   B booked({booked}) <= capacity({capacity}): "
          f"{'PASS' if booked <= capacity else 'FAIL'}")
    print(f"   C orphaned holds swept by survivor: {'PASS' if int(orphans) == 0 else 'FAIL'}")
    print(f"   D outbox drained by survivor: {'PASS' if int(stuck) == 0 else 'FAIL'}")
    print(f"   gateway serving after failover: {'yes' if recovered else 'NO'}")
    availability_errors = sum(v for k, v in counts.items() if "5xx" in k or "599" in str(k))
    print(f"   availability degradation during failover: "
          f"{availability_errors} failed requests (expected > 0, tolerated)")

    if failures:
        print("\nINVARIANT VIOLATIONS:")
        for f in failures:
            print(" - " + f)
        sys.exit(1)
    print("\nPASS: correctness invariants held through SIGKILL under load.")


if __name__ == "__main__":
    main()

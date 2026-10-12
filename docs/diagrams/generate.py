#!/usr/bin/env python3
"""Generates the SVG diagrams in this directory (standard library only).

    python3 docs/diagrams/generate.py

Every diagram paints an explicit white background so it renders identically on
GitHub in light and dark mode. Edit the figures below and re-run the script.
"""
from __future__ import annotations

import html
from pathlib import Path

OUT = Path(__file__).resolve().parent

FONT = 'Inter, -apple-system, "Segoe UI", Helvetica, Arial, sans-serif'
MONO = 'SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace'
INK = "#1f2937"
MUTED = "#64748b"
LINE = "#475569"
BORDER = "#cbd5e1"
ASYNC = "#15803d"
WARN = "#b45309"

# fill, stroke
TINT = {
    "client": ("#eef2ff", "#a5b4fc"),
    "edge": ("#ecfeff", "#67e8f9"),
    "service": ("#ffffff", "#94a3b8"),
    "data": ("#fff7ed", "#fdba74"),
    "cache": ("#fef2f2", "#fca5a5"),
    "msg": ("#f0fdf4", "#86efac"),
    "ext": ("#f8fafc", "#cbd5e1"),
    "note": ("#fefce8", "#fde68a"),
    "plain": ("#ffffff", "#cbd5e1"),
}


class Svg:
    def __init__(self, w: int, h: int, title: str, subtitle: str | None = None):
        self.w, self.h = w, h
        self.parts: list[str] = []
        self.markers: dict[str, str] = {}
        self.text(30, 44, title, size=21, weight=600)
        if subtitle:
            self.text(30, 66, subtitle, size=12.5, fill=MUTED)

    # -- primitives -----------------------------------------------------------
    def text(self, x, y, s, size=12, fill=INK, weight=400, anchor="start",
             halo=False, mono=False, italic=False):
        a = [f'x="{x}" y="{y}" font-size="{size}" fill="{fill}"']
        if weight != 400:
            a.append(f'font-weight="{weight}"')
        if anchor != "start":
            a.append(f'text-anchor="{anchor}"')
        if halo:
            a.append('stroke="#ffffff" stroke-width="4" paint-order="stroke" stroke-linejoin="round"')
        if mono:
            a.append(f'font-family=\'{MONO}\'')
        if italic:
            a.append('font-style="italic"')
        self.parts.append(f"<text {' '.join(a)}>{html.escape(s)}</text>")

    def label(self, x, y, s, size=10.5, fill=MUTED, anchor="middle", mono=False, weight=400):
        self.text(x, y, s, size=size, fill=fill, anchor=anchor, halo=True, mono=mono, weight=weight)

    def rect(self, x, y, w, h, fill="#fff", stroke=BORDER, rx=8, dashed=False, width=1.2):
        dash = ' stroke-dasharray="6 4"' if dashed else ""
        self.parts.append(
            f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
            f'stroke="{stroke}" stroke-width="{width}"{dash}/>')

    def marker_id(self, color: str) -> str:
        mid = "arrow-" + color.lstrip("#")
        self.markers[mid] = color
        return mid

    def path(self, d, color=LINE, width=1.5, dashed=False, arrow=True):
        dash = ' stroke-dasharray="6 4"' if dashed else ""
        m = f' marker-end="url(#{self.marker_id(color)})"' if arrow else ""
        self.parts.append(
            f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{width}" '
            f'stroke-linejoin="round" stroke-linecap="round"{dash}{m}/>')

    def arrow(self, pts, color=LINE, width=1.5, dashed=False, arrow=True):
        d = "M " + " L ".join(f"{x} {y}" for x, y in pts)
        self.path(d, color, width, dashed, arrow)

    # -- composites -----------------------------------------------------------
    def box(self, x, y, w, h, title=None, body=(), kind="service", dashed=False,
            title_size=13, body_size=11, lh=15, subtitle=None, align="middle",
            title_fill=INK, body_fill=INK, mono_body=False):
        fill, stroke = TINT[kind]
        self.rect(x, y, w, h, fill, stroke, dashed=dashed)
        n_title = (1 if title else 0) + (1 if subtitle else 0)
        block = n_title * (title_size + 5) + len(body) * lh
        cy = y + (h - block) / 2 + title_size
        tx = x + w / 2 if align == "middle" else x + 14
        if title:
            self.text(tx, cy, title, size=title_size, weight=600, anchor=align, fill=title_fill)
            cy += title_size + 5
        if subtitle:
            self.text(tx, cy - 2, subtitle, size=body_size, fill=MUTED, anchor=align)
            cy += title_size + 3
        for line in body:
            self.text(tx, cy - 1, line, size=body_size, fill=body_fill, anchor=align, mono=mono_body)
            cy += lh

    def cylinder(self, x, y, w, h, title, body=(), kind="data"):
        fill, stroke = TINT[kind]
        ry = 7
        self.parts.append(
            f'<path d="M {x} {y + ry} A {w / 2} {ry} 0 0 1 {x + w} {y + ry} L {x + w} {y + h - ry} '
            f'A {w / 2} {ry} 0 0 1 {x} {y + h - ry} Z" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>')
        self.parts.append(
            f'<path d="M {x} {y + ry} A {w / 2} {ry} 0 0 0 {x + w} {y + ry}" fill="none" '
            f'stroke="{stroke}" stroke-width="1.2"/>')
        cy = y + ry + 18
        self.text(x + w / 2, cy, title, size=11.5, weight=600, anchor="middle", mono=True)
        for line in body:
            cy += 14
            self.text(x + w / 2, cy, line, size=10.5, fill=MUTED, anchor="middle")

    def group(self, x, y, w, h, label=None, fill="none", stroke=BORDER, dashed=True, label_fill=MUTED):
        self.rect(x, y, w, h, fill, stroke, rx=10, dashed=dashed)
        if label:
            self.text(x + 12, y + 18, label, size=11.5, fill=label_fill, weight=600)

    def legend(self, y, items):
        x = 30
        for color, dashed, text in items:
            self.arrow([(x, y), (x + 40, y)], color=color, dashed=dashed)
            self.text(x + 50, y + 4, text, size=11, fill=MUTED)
            x += 60 + len(text) * 6.2

    def save(self, name: str):
        defs = "".join(
            f'<marker id="{mid}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" '
            f'orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="{color}"/></marker>'
            for mid, color in self.markers.items())
        svg = (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" '
            f'viewBox="0 0 {self.w} {self.h}" font-family=\'{FONT}\'>\n'
            f'<defs>{defs}</defs>\n'
            f'<rect width="{self.w}" height="{self.h}" fill="#ffffff"/>\n'
            + "\n".join(self.parts) + "\n</svg>\n")
        (OUT / name).write_text(svg)
        print("wrote", name)


# ---------------------------------------------------------------------------
def system_architecture():
    s = Svg(1200, 790, "SeatSync system architecture",
            "Six Spring Boot services behind a gateway, one Angular SPA, PostgreSQL, Redis and Kafka. "
            "The whole stack runs with docker compose up.")

    # client and edge
    s.box(430, 96, 340, 58, "Browser · Angular 21 SPA", ["served by nginx on :4200 · signals · Angular Material · STOMP over SockJS"],
          kind="client")
    s.arrow([(600, 154), (600, 200)])
    s.label(612, 181, "REST /api/**  ·  WebSocket /ws", anchor="start")
    s.box(430, 200, 340, 74, "api-gateway :8080",
          ["Spring Cloud Gateway (WebFlux)", "edge JWT validation · per-IP rate limiting (Redis token buckets) · CORS"],
          kind="edge")
    s.box(985, 200, 180, 74, "Zipkin :9411", ["traces from the gateway", "and every service"], kind="ext", dashed=True)

    # bus from the gateway to every service
    xs = [35, 272.5, 510, 747.5, 985]
    w, top, h = 180, 340, 118
    centers = [x + w / 2 for x in xs]
    s.arrow([(600, 274), (600, 304)], arrow=False)
    s.arrow([(centers[0], 304), (centers[-1], 304)], arrow=False)
    for cx in centers:
        s.arrow([(cx, 304), (cx, top)])

    services = [
        ("ai-concierge-service", ":8085", ["Spring AI 1.1 · RAG over policy docs", "tool calls into catalog and booking", "offline mode without an API key"]),
        ("catalog-service", ":8082", ["events · venues · derived seat maps", "Redis cache-aside on hot reads"]),
        ("booking-service", ":8083", ["5-minute holds · optimistic-lock confirm", "waitlist · expiry sweeper · outbox relay", "STOMP /ws live seat updates"]),
        ("notification-service", ":8084", ["Kafka consumer → email", "dedupe on messageId · retry topics · DLT"]),
        ("auth-service", ":8081 (host 18081)", ["register · login · refresh rotation · logout", "roles ATTENDEE · ORGANIZER · ADMIN"]),
    ]
    for x, (name, port, body) in zip(xs, services):
        s.box(x, top, w, h, name, body, subtitle=port, body_size=10.5, lh=14)

    # synchronous calls between services
    s.arrow([(xs[0] + w, 392), (xs[1], 392)])
    s.label((xs[0] + w + xs[1]) / 2, 386, "REST", size=10)
    s.arrow([(xs[2], 392), (xs[1] + w, 392)])
    s.label((xs[2] + xs[1] + w) / 2, 386, "REST", size=10)
    hop = centers[1]
    s.path(f"M {xs[0] + 160} {top} L {xs[0] + 160} 318 L {hop - 6} 318 A 6 6 0 0 1 {hop + 6} 318 L 540 318 L 540 {top}")
    s.label(455, 333, "REST tool calls (live availability)", size=10)

    # databases
    s.rect(20, 488, 1160, 102, fill="#fffaf3", stroke="none", rx=10)
    s.text(62, 582, "PostgreSQL 16 · one database per service · Flyway migrations", size=11.5, fill=WARN, weight=600)
    dbs = [("seatsync_ai", ["pgvector"]), ("seatsync_catalog", []), ("seatsync_booking", []),
           ("seatsync_notification", []), ("seatsync_auth", [])]
    for cx, (name, body) in zip(centers, dbs):
        s.arrow([(cx, top + h), (cx, 512)])
        s.cylinder(cx - 65, 512, 130, 48, name, body)

    # row of other infrastructure
    ey, eh = 612, 74
    s.box(20, ey, 180, eh, "OpenAI API (optional)", ["chat + embeddings via Spring AI", "offline mode without a key"], kind="ext", dashed=True, body_size=10.5, lh=14)
    s.arrow([(xs[0] + 12, top + h), (xs[0] + 12, ey)], dashed=True)
    s.box(350, ey, 230, eh, "Redis 7", ["seat holds (SET NX PX) · catalog cache-aside", "rate-limit buckets · STOMP fan-out (pub/sub)"], kind="cache", body_size=10.5, lh=14)
    s.arrow([(xs[1] + w - 12, top + h), (xs[1] + w - 12, ey)])
    s.arrow([(xs[2] + 12, top + h), (xs[2] + 12, ey)])
    s.box(630, ey, 220, eh, "Kafka 3.9 (KRaft)", ["booking.confirmed · hold.expired", "waitlist.offered", "3 partitions · key = eventId"], kind="msg", body_size=10.5, lh=14)
    s.arrow([(xs[2] + w - 12, top + h), (xs[2] + w - 12, ey)], color=ASYNC, width=2)
    s.label(xs[2] + w - 24, 601, "outbox relay · every 2 s", anchor="end", size=10)
    s.arrow([(xs[3] + 12, ey), (xs[3] + 12, top + h)], color=ASYNC, width=2)
    s.label(xs[3] + 18, 601, "consume", anchor="start", size=10)
    s.box(890, ey, 150, eh, "Mailpit :8025", ["SMTP sandbox", "sent-email UI"], kind="ext", body_size=10.5, lh=14)
    s.arrow([(xs[3] + w - 12, top + h), (xs[3] + w - 12, ey)])

    s.legend(726, [(LINE, False, "synchronous REST or WebSocket"),
                   (ASYNC, False, "asynchronous via Kafka (transactional outbox, at-least-once)"),
                   (LINE, True, "optional external dependency")])
    s.text(30, 762, "Each service owns its database and never imports another service's code (enforced by ArchUnit). "
                    "The shared contract is docs/CONVENTIONS.md, the JSON Schemas and the Pact files.", size=11, fill=MUTED)
    s.save("system-architecture.svg")


# ---------------------------------------------------------------------------
def booking_sequence():
    s = Svg(1270, 890, "Booking flow: hold, then confirm",
            "Two requests, three guards. Redis absorbs contention; the hold row and the @Version update in PostgreSQL make the decision.")
    U, B, R, P, K, N = 90, 320, 545, 765, 975, 1150
    heads = [(U, "User (SPA)", "", "client"), (B, "booking-service", "", "service"), (R, "Redis", "hold:{event}:{seat}", "cache"),
             (P, "PostgreSQL", "holds · seat_inventory · bookings · outbox_events", "data"), (K, "Kafka", "", "msg"),
             (N, "notification-service", "", "service")]
    for x, name, sub, kind in heads:
        bw = 190 if x == P else 150
        s.box(x - bw / 2, 86, bw, 46, name, [sub] if sub else [], kind=kind, body_size=9.5, lh=12)
        s.path(f"M {x} 132 L {x} 830", color=BORDER, dashed=True, arrow=False)

    def bar(x, y1, y2):
        s.rect(x - 5, y1, 10, y2 - y1, fill="#e2e8f0", stroke="#94a3b8", rx=2)

    def msg(a, b, y, text, dashed=False, color=LINE, width=1.5, size=10.5):
        s.arrow([(a, y), (b, y)], color=color, dashed=dashed, width=width)
        s.label((a + b) / 2, y - 6, text, size=size, fill=INK)

    def frame(x1, y1, x2, y2, kind, guard):
        s.rect(x1, y1, x2 - x1, y2 - y1, fill="none", stroke="#94a3b8", rx=4)
        s.rect(x1, y1, 34, 18, fill="#f1f5f9", stroke="#94a3b8", rx=4)
        s.text(x1 + 17, y1 + 13, kind, size=10.5, weight=600, anchor="middle")
        s.text(x1 + 42, y1 + 13, guard, size=10.5, fill=MUTED, italic=True)

    def divider(x1, x2, y, guard):
        s.path(f"M {x1} {y} L {x2} {y}", color="#94a3b8", dashed=True, arrow=False)
        s.text(x1 + 8, y + 13, guard, size=10.5, fill=MUTED, italic=True)

    bar(B, 170, 378)
    msg(U, B, 170, "POST /api/holds {eventId, seatId}")
    msg(B, R, 200, "SET hold:{event}:{seat} NX PX 300000")
    frame(55, 218, 600, 392, "alt", "[key already exists]")
    msg(R, B, 248, "nil", dashed=True)
    msg(B, U, 276, "409 seat already held", dashed=True)
    divider(55, 600, 296, "[claimed]")
    msg(B, P, 324, "INSERT holds (status HELD, expires_at = now + 5 min)")
    msg(B, U, 352, "201 {holdId, expiresAt}", dashed=True)
    msg(B, U, 376, "WS: seat HELD (relayed to all instances via Redis pub/sub)", dashed=True, color=MUTED)

    bar(B, 430, 692)
    msg(U, B, 430, "POST /api/bookings {holdId}")
    msg(B, P, 458, "in one transaction: re-read the hold → owner = caller · status = HELD · expires_at > now")
    msg(B, P, 486, "UPDATE seat_inventory SET status = BOOKED, version = n + 1 WHERE id = ? AND version = n")
    frame(55, 504, 820, 708, "alt", "[version conflict]")
    msg(P, B, 534, "OptimisticLockingFailure → rollback", dashed=True)
    msg(B, U, 562, "409 seat was just taken", dashed=True)
    divider(55, 820, 582, "[committed]")
    msg(B, P, 610, "INSERT bookings · hold → CONFIRMED · INSERT outbox_events · COMMIT")
    msg(B, R, 638, "DEL hold:{event}:{seat}")
    msg(B, U, 666, "201 CONFIRMED {bookingId}", dashed=True)
    msg(B, U, 690, "WS: seat BOOKED", dashed=True, color=MUTED)

    bar(B, 744, 750)
    msg(B, K, 746, "OutboxRelay, every 2 s: claim rows FOR UPDATE SKIP LOCKED → publish BookingConfirmed → mark published_at",
        color=ASYNC, width=2)
    msg(K, N, 774, "consume (at-least-once)", color=ASYNC, width=2)
    s.box(N - 150, 790, 260, 40, None, ["claim message_id (UNIQUE) → send → SENT", "a redelivery is a logged no-op"],
          kind="note", body_size=10, lh=13)

    s.text(30, 866, "Correctness never depends on Redis: a lost or expired key can at most let a rival reach the UPDATE, "
                    "where the version check admits exactly one commit.", size=11, fill=MUTED)
    s.save("booking-sequence.svg")


# ---------------------------------------------------------------------------
def oversell_guards():
    s = Svg(1200, 470, "How a seat sells at most once",
            "Three independent layers. The last one alone guarantees correctness; the first two make contention cheap and holds authoritative.")
    y, h, w = 110, 205, 260
    s.box(30, 190, 130, 70, None, ["N concurrent", "requests for", "one seat"], kind="client", body_size=11.5, lh=16)
    cards = [
        (190, "cache", "1 · Atomic hold claim", "Redis: SET hold:{event}:{seat} NX PX 300000",
         ["One winner per seat per 5-minute window.", "Losers get 409 in under a millisecond,", "before any database transaction opens.", "",
          "If Redis is down: new holds fail fast and", "clean; nothing below trusts the key."]),
        (480, "data", "2 · Authoritative hold row", "PostgreSQL: holds (status, expires_at)",
         ["Confirm re-reads owner, status and expiry", "from the row inside its own transaction.", "The Redis TTL is never consulted.", "",
          "A sweeper expires stale holds every 10 s", "(SKIP LOCKED claims, safe under replicas)."]),
        (770, "data", "3 · Optimistic lock on the seat", "PostgreSQL: seat_inventory.version (@Version)",
         ["UPDATE … WHERE version = n: exactly one", "concurrent transaction commits BOOKED.", "The other gets a clean 409, never a second sale.", "",
          "OptimisticLockGuardIT: 60 valid holds on", "one seat → exactly 1 booking."]),
    ]
    for x, kind, title, tech, body in cards:
        fill, stroke = TINT[kind]
        s.rect(x, y, w, h, "#ffffff", stroke)
        s.parts.append(f'<path d="M {x} {y + 10} A 10 10 0 0 1 {x + 10} {y} L {x + w - 10} {y} A 10 10 0 0 1 {x + w} {y + 10} '
                       f'L {x + w} {y + 56} L {x} {y + 56} Z" fill="{fill}" stroke="{stroke}" stroke-width="1.2"/>')
        s.text(x + 14, y + 24, title, size=13.5, weight=600)
        s.text(x + 14, y + 43, tech, size=9.2, fill=MUTED, mono=True)
        cy = y + 80
        for line in body:
            s.text(x + 14, cy, line, size=11)
            cy += 16 if line else 8
    s.box(1060, 190, 110, 70, None, ["exactly", "1 BOOKED", "seat"], kind="msg", body_size=11.5, lh=16)
    for a, b in [(160, 190), (450, 480), (740, 770), (1030, 1060)]:
        s.arrow([(a, 225), (b, 225)], width=2)
    outs = [("losers: 409 in < 1 ms", 320), ("expired or released hold: 409", 610), ("version conflict: 409", 900)]
    for text, cx in outs:
        s.arrow([(cx, y + h), (cx, y + h + 26)], color=MUTED)
        s.label(cx, y + h + 42, text, fill=MUTED, size=10.5)
    s.text(30, 420, "Measured: 60 threads against 10 seats → exactly 10 bookings and no 5xx (ConcurrentBookingRaceIT); "
                    "150 buyers against 44 seats → 44 bookings, 74 × 409, 0 oversold (k6 through the gateway).", size=11, fill=MUTED)
    s.save("oversell-guards.svg")


# ---------------------------------------------------------------------------
def event_pipeline():
    s = Svg(1270, 570, "Event pipeline: transactional outbox to email",
            "Facts that already happened travel over Kafka. The HTTP path never touches the broker; a Kafka outage delays notifications and never loses them.")
    y, h = 120, 110
    s.box(30, y, 230, h, "booking-service transaction", ["booking or hold row change", "+ outbox_events row", "one COMMIT, no dual write"], body_size=10.5, lh=14)
    s.box(300, y, 240, h, "OutboxRelay", ["claim ≤ 100 rows FOR UPDATE SKIP LOCKED", "send().get() → mark published_at", "broker timeout → stop, retry next cycle"],
          subtitle="every 2 s, on every replica", body_size=10.5, lh=14)
    s.box(580, y, 230, h, "Kafka", ["seatsync.booking.confirmed", "seatsync.hold.expired", "seatsync.waitlist.offered", "3 partitions · key = eventId"],
          kind="msg", body_size=10.5, lh=14)
    s.box(860, y, 230, h, "notification-service", ["1. claim message_id (UNIQUE) → SENDING", "2. compose + send over SMTP", "3. SENT · duplicate delivery → no-op"],
          body_size=10.5, lh=14)
    s.box(1130, y, 110, h, "Mailpit", [":8025", "SMTP sandbox"], kind="ext", body_size=10.5, lh=14)
    s.arrow([(260, 195), (300, 195)]); s.label(280, 187, "poll", size=10)
    s.arrow([(540, 195), (580, 195)], color=ASYNC, width=2); s.label(560, 187, "publish", size=10)
    s.arrow([(810, 195), (860, 195)], color=ASYNC, width=2); s.label(835, 187, "consume", size=10)
    s.arrow([(1090, 195), (1130, 195)]); s.label(1110, 187, "SMTP", size=10)

    ry, rh = 360, 90
    s.box(720, ry, 280, rh, "retry topics", ["<topic>-retry-1000 → -retry-2000 → -retry-4000", "4 attempts in total, exponential backoff", "transient failures only (SMTP, database)"],
          kind="msg", dashed=True, body_size=10.5, lh=14)
    s.box(1060, ry, 180, rh, "<topic>-dlt", ["attempts exhausted, or", "poison: unparseable JSON,", "schema violation"], kind="note", body_size=10.5, lh=14)
    s.arrow([(975, y + h), (975, ry)]); s.label(981, 300, "transient failure", anchor="start", size=10)
    s.arrow([(760, ry), (760, 300), (900, 300), (900, y + h)]); s.label(830, 294, "redeliver after the delay", size=10)
    s.arrow([(1000, 405), (1060, 405)]); s.label(1030, 398, "exhausted", size=9.5)
    s.arrow([(1070, y + h), (1070, 280), (1150, 280), (1150, ry)]); s.label(1144, 330, "poison: straight to the DLT", anchor="end", size=10)
    s.box(760, 480, 480, 56, None, ["Runbook (docs/runbook-dlt-replay.md): inspect the DLT, fix the cause, pipe messages back", "to the main topic. Replay is safe at any time because consumption is idempotent on messageId."],
          kind="ext", dashed=True, body_size=10.5, lh=14)
    s.arrow([(1150, ry + rh), (1150, 480)])
    s.arrow([(760, 508), (695, 508), (695, y + h)], dashed=True); s.label(689, 380, "replay", anchor="end", size=10)
    s.save("event-pipeline.svg")


# ---------------------------------------------------------------------------
def seat_lifecycle():
    s = Svg(1100, 520, "Seat lifecycle", "Every transition that frees or books a seat writes an outbox row in the same transaction and broadcasts the new state over STOMP.")
    y, w, h = 200, 160, 60
    states = [(60, "AVAILABLE", "plain"), (470, "HELD", "cache"), (880, "BOOKED", "msg")]
    for x, name, kind in states:
        fill, stroke = TINT[kind]
        s.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="30" fill="{fill}" stroke="{stroke}" stroke-width="1.4"/>')
        s.text(x + w / 2, y + 36, name, size=15, weight=600, anchor="middle")
    s.arrow([(220, 218), (470, 218)], width=1.8)
    s.label(345, 210, "POST /holds · SET NX + hold row, 5 min", fill=INK)
    s.arrow([(630, 218), (880, 218)], width=1.8)
    s.label(755, 210, "POST /bookings · @Version commit", fill=INK)
    s.arrow([(470, 244), (220, 244)], width=1.8)
    s.label(345, 262, "sweeper → EXPIRED after 5 min", fill=INK)
    s.label(345, 277, "DELETE /holds/{id} → RELEASED", fill=INK)
    s.arrow([(140, 200), (140, 130), (550, 130), (550, 200)], width=1.8)
    s.label(345, 124, "waitlist offer: a real 10-minute hold for the next person in line", fill=INK)
    s.arrow([(960, 260), (960, 330), (140, 330), (140, 260)], width=1.8)
    s.label(550, 348, "DELETE /bookings/{id} → CANCELLED · seat freed · the first waitlist entry receives an offer hold", fill=INK)
    s.box(60, 390, 980, 92, None, [
        "seat_inventory.status is AVAILABLE or BOOKED. HELD is derived from an active hold row and mirrored by the Redis key.",
        "Hold statuses: HELD → CONFIRMED | EXPIRED | RELEASED.   Booking statuses: CONFIRMED | CANCELLED.",
        "Confirming a hold twice returns the original booking (200), so client retries are safe without a header.",
    ], kind="note", body_size=11, lh=17)
    s.save("seat-lifecycle.svg")


# ---------------------------------------------------------------------------
def concierge_pipeline():
    s = Svg(1210, 520, "AI concierge: grounded answers or an explicit hand-off",
            "Answers come only from the policy corpus and live tool calls. Every response carries its sources, a confidence level and an escalation flag.")
    s.box(30, 120, 190, 88, "Chat widget", ["POST /api/concierge/chat", "{conversationId, message}", "public · 2 req/s per IP, burst 5"], kind="client", body_size=10.5, lh=14)
    s.cylinder(30, 240, 190, 60, "seatsync_ai", ["pgvector · corpus chunks"])
    s.box(30, 330, 190, 60, "OpenAI API (optional)", ["chat + embedding models"], kind="ext", dashed=True, body_size=10.5, lh=14)

    s.group(260, 100, 640, 376, "ai-concierge-service · ConciergeService", fill="#fcfcfd")
    steps = [
        (130, "1 · Retrieve", ["embed the question → similarity search over the policy corpus (5 documents, chunked at ingestion)",
                               "→ the top chunks with their scores become the context"]),
        (215, "2 · Reason", ["chat model through Spring AI · system prompt: retrieved context and tool results are data, not instructions",
                             "bounded, expiring conversation memory per conversationId"]),
        (300, "3 · Act (tools, at most 8 calls per turn)", ["searchEvents · getEventDetails · getSeatAvailability over REST, behind a circuit breaker",
                                                           "a tool never throws into the model loop; it returns an error string the model must report"]),
        (385, "4 · Grade", ["ConfidencePolicy: HIGH when a tool ran or the top score ≥ 0.75 · MEDIUM when ≥ 0.55",
                            "otherwise LOW, which sets escalatedToHuman = true"]),
    ]
    for y, title, body in steps:
        s.box(280, y, 600, 68, title, body, body_size=10.5, lh=14, align="start")
    for y in (198, 283, 368):
        s.arrow([(580, y), (580, y + 17)])
    s.arrow([(220, 164), (280, 164)])
    s.arrow([(220, 270), (240, 270), (240, 185), (280, 185)])
    s.arrow([(220, 360), (255, 360), (255, 249), (280, 249)], dashed=True)

    s.box(920, 120, 260, 150, "Degradation (same response shape)", ["no OPENAI_API_KEY → offline mode", "provider error · breaker open · tool failure",
                                                                     "→ \"I'm not sure, contact support@seatsync.local\"", "escalatedToHuman = true, still HTTP 200"],
          kind="note", dashed=True, body_size=10.5, lh=15)
    s.arrow([(880, 249), (920, 249)], dashed=True)
    s.box(920, 300, 260, 64, "catalog-service · booking-service", ["events, details, live seat availability"], body_size=10.5, lh=14)
    s.arrow([(880, 334), (920, 334)])
    s.box(920, 390, 260, 86, "Response (always HTTP 200)", ["answer · sources[] (title, snippet, score)", "confidence: HIGH | MEDIUM | LOW", "escalatedToHuman: true | false"],
          kind="msg", body_size=10.5, lh=14)
    s.arrow([(880, 419), (920, 419)], width=2)
    s.text(30, 506, "The chat endpoint has its own, smaller rate limit at the gateway because every call costs model tokens. "
                    "Without a key the service still starts and reports healthy; booking is never affected.", size=11, fill=MUTED)
    s.save("concierge-pipeline.svg")


# ---------------------------------------------------------------------------
def chaos_harness():
    s = Svg(1100, 600, "Chaos harness: availability may degrade, correctness may not",
            "booking-service reaches every dependency through Toxiproxy; each scenario injects a failure and then checks the oversell invariant.")
    s.box(430, 240, 240, 110, "booking-service under test", ["real Spring Boot context (Testcontainers)", "every connection goes through a", "Toxiproxy listener"], body_size=10.5, lh=14)
    deps = [
        (120, "Redis", ["down mid-booking", "+3 s latency on every call"], "cache"),
        (220, "Kafka", ["broker down through", "a sales window"], "msg"),
        (320, "PostgreSQL", ["+2 s latency · pool of 3", "30 concurrent buyers"], "data"),
        (420, "catalog-service", ["blackholed listener", "(network partition)"], "service"),
    ]
    s.group(725, 80, 350, 420, "Toxiproxy scenarios", fill="#fcfcfd")
    s.text(737, 492, "ChaosToleranceIT · ChaosPoolExhaustionIT · ChaosCatalogPartitionIT", size=9.5, fill=MUTED)
    for y, name, body, kind in deps:
        s.box(745, y, 100, 44, "proxy", [], kind="ext", title_size=11)
        s.box(875, y - 10, 180, 64, name, body, kind=kind, body_size=10.5, lh=14)
        s.arrow([(670, 295), (745, y + 22)])
        s.arrow([(845, y + 22), (875, y + 22)])
    s.box(40, 240, 300, 110, "Kill-the-node (live Compose stack)", ["two booking replicas, 12 users storming a 66-seat event",
                                                                    "SIGKILL one replica at t+6 s of 30 s", "survivor sweeps orphaned holds and drains the outbox"],
          kind="ext", dashed=True, body_size=10.5, lh=14)
    s.arrow([(340, 295), (430, 295)], dashed=True)
    s.label(385, 287, "infra/chaos", size=10)
    s.box(40, 500, 1035, 80, "Asserted after every scenario", ["no (event_id, seat_id) with two CONFIRMED bookings · bookings never exceed capacity · "
                                                               "failures are fast and bounded (no hangs) · recovery without a restart"],
          kind="note", body_size=11, lh=15)
    s.text(557, 568, "SELECT event_id, seat_id FROM bookings WHERE status = 'CONFIRMED' GROUP BY event_id, seat_id HAVING count(*) > 1  →  0 rows",
           size=9.5, fill=MUTED, anchor="middle", mono=True)
    s.arrow([(550, 350), (550, 500)], width=2)
    s.label(556, 430, "oversell check", anchor="start", size=10)
    s.save("chaos-harness.svg")

if __name__ == "__main__":
    system_architecture()
    booking_sequence()
    oversell_guards()
    event_pipeline()
    seat_lifecycle()
    concierge_pipeline()
    chaos_harness()

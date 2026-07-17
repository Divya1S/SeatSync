# Walkthrough video script (2–3 minutes)

Record with two browser windows side by side (two different logged-in users)
+ a terminal. Suggested tool: QuickTime / OBS, 1080p.

| t | Shot | Say |
|---|---|---|
| 0:00–0:15 | Terminal: `docker compose up` already running; browser on http://localhost:4200 | "SeatSync is an event-driven microservices booking platform — six Spring Boot services, Kafka, Redis, Postgres, an Angular front end — built around one invariant: a seat can never be sold twice." |
| 0:15–0:40 | Browse events → open "Friday Night Jazz" seat map | "Attendees browse events served by the catalog service — hot reads come from a Redis cache-aside layer. This is the live seat map, streamed over STOMP WebSockets." |
| 0:40–1:20 | Window A: click a seat → hold countdown appears. Window B: same seat instantly greys out; B clicks it anyway → 409 toast | "Clicking a seat takes a 5-minute hold — an atomic Redis SET-NX claim. Watch the second user: the seat greys out in real time, and trying to grab it fails cleanly. Confirming the booking runs the final guard: a JPA optimistic-lock transition from AVAILABLE to BOOKED, so even if Redis lost every hold, only one buyer can ever win a seat." |
| 1:20–1:40 | Confirm booking in A; open Mailpit :8025 showing the confirmation email; flash Zipkin trace | "On confirm, a BookingConfirmed event goes to Kafka; the notification service emails the ticket — here in the Mailpit sandbox — and the whole hold→book→notify flow is traced in Zipkin. If Kafka is down, bookings still succeed: publishing is fire-and-forget behind a circuit breaker." |
| 1:40–2:10 | Terminal: `cd booking-service && ./mvnw test -Dtest=ConcurrentBookingRaceIT` (pre-warmed) showing green | "Zero overselling isn't a claim, it's a test: 60 simultaneous buyers race for 10 seats against real Postgres, Redis and Kafka in Testcontainers — exactly 10 bookings succeed, everyone else gets a clean 409." |
| 2:10–2:40 | AI chat widget: ask "Are there seats left for Friday Night Jazz?" then "Can I get a refund 1 hour before?" | "The AI concierge is RAG-grounded over the real policy docs and calls the live catalog and booking APIs — it shows its sources and confidence, and when it isn't sure, it hands you to a human instead of guessing." |
| 2:40–3:00 | Organizer dashboard live sales view | "Organizers get live sales as seats are taken. Everything you saw runs locally with one docker compose up. Thanks for watching." |

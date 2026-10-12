# SeatSync frontend

Angular 21 single-page app (standalone components, signals, Angular Material)
for browsing events, holding and booking seats on a live seat map, managing
bookings, the organizer dashboard, and the AI concierge widget.

## Develop

```bash
npm ci
npm start          # http://localhost:4200, /api and /ws proxied to the gateway on :8080
```

`proxy.conf.json` forwards REST (`/api`) and the STOMP WebSocket (`/ws`) to the
API gateway, so the backend stack (`docker compose up` at the repository root)
must be running.

## Test and build

```bash
npm test           # Karma + Jasmine, ChromeHeadless
npm run build      # production bundle in dist/frontend/browser (budgets: 800 kB warn, 1 MB error)
```

## Container

`Dockerfile` builds the bundle and serves it with nginx (`nginx.conf`): SPA
fallback to `index.html`, `/api/` and `/ws` proxied to `api-gateway:8080` with
WebSocket upgrade headers, and baseline security headers.

## Where things live

| Path | Contents |
|---|---|
| `src/app/core/` | auth service + interceptor (bearer, single refresh-and-retry, logout), guards, typed API models, catalog/booking/concierge clients, STOMP socket service, pure seat-state and countdown helpers |
| `src/app/features/` | lazy-loaded routes: events list and detail (live seat map), login/register, my bookings, organizer dashboard and event form, concierge widget |

Seat states follow the convention in `docs/CONVENTIONS.md` §9: available
(outlined), held by you (accent + countdown), held by others (greyed, disabled),
booked (dark, disabled). Seat buttons carry `aria-label`s with the seat id,
price and state so colour is never the only signal.

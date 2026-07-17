# SeatSync Holds and Booking Rules

SeatSync sells reserved seating: every ticket is for one specific seat. A seat
is identified by its section, row and number joined by dashes — for example
seat **A-3-12** is section A, row 3, seat 12. Getting a ticket is always a
two-step process: first you **hold** the seat, then you **confirm** the hold
into a booking.

## Step 1 — the 5-minute hold

Clicking an available seat places a **hold that lasts exactly 5 minutes**
(300 seconds). While your hold is active:

- The seat is exclusively yours — nobody else can hold or book it.
- **Only one active hold can exist per seat, platform-wide.** Holds are
  first-come, first-served; if two people click the same seat at nearly the
  same moment, exactly one of them gets the hold and the other sees a
  "seat is already held" message.
- A visible countdown shows the time remaining on your hold.
- Holds are free. You are never charged for a hold, only for a confirmed
  booking.

You may hold **several different seats at the same time** (for example when
buying for a group); each hold has its own independent 5-minute countdown.

When the countdown reaches zero the hold **expires automatically** and the
seat returns to the open pool within a few seconds — no action needed from
anyone. You can also release a hold early yourself, which frees the seat
immediately. An expired hold can never be confirmed; trying to confirm one is
rejected and you must place a fresh hold.

## Step 2 — confirming the booking

Confirming converts your hold into a paid booking. At the instant of
confirmation SeatSync performs a **final availability check on the seat**
(an optimistic check). In the extremely rare case that the check fails — for
example the platform detects a conflicting update on the same seat — the
confirmation is rejected with a "Seat was just taken" error and you are not
charged. If that happens, simply choose another seat. A successful
confirmation produces a booking with a booking ID, and a confirmation email is
sent to you.

Bookings, holds and the waitlist all require a signed-in account. Browsing
events and viewing live seat maps works anonymously.

## Seat states on the live seat map

Every seat is in exactly one of three states, updated live for everyone
viewing the seat map:

- **AVAILABLE** — free to hold.
- **HELD** — someone has an active 5-minute hold. If it is your hold, you see
  your countdown; other people just see the seat as unavailable.
- **BOOKED** — confirmed and paid.

## The waitlist

If an event has no available seats you can **join its waitlist** (one entry
per person per event, free of charge). Whenever a seat frees up — because a
hold expired, a hold was released or a booking was cancelled — the seat is
offered to the person who has been on the waitlist the longest. The offer is
sent by email and is **valid for 10 minutes**; if it is not used in time, the
seat passes to the next person in line. Leaving the waitlist is possible at
any time and costs nothing.

## Quick rules recap

1. Hold duration: exactly 5 minutes, non-extendable.
2. One active hold per seat across the whole platform.
3. Multiple seats may be held by one person simultaneously.
4. Holds are free; payment happens only at confirmation.
5. Confirmation performs a final seat check — "Seat was just taken" means you
   must pick another seat, and nothing was charged.
6. Expired holds cannot be confirmed.
7. Freed seats go to the oldest waitlist entry first, with a 10-minute offer
   window.

# SeatSync Organizer Guide

This guide is for event organizers. Organizer features require an account
with the **ORGANIZER** role, which you choose when registering. Attendee
accounts cannot create events.

## Creating an event

An event is created as a **DRAFT** with the following details:

- **Name and description** — shown to attendees and searchable.
- **Category** — one of CONCERT, WORKSHOP, SPORTS, CAMPUS or OTHER.
- **Venue** — picked from SeatSync's venue list (name, city, address). You can
  register a new venue if yours is not listed yet.
- **Start and end time** — stored and displayed in UTC.
- **Sections** — the seating layout, described per section:
  - section name (for example "A" or "Balcony"),
  - number of rows and seats per row,
  - price tier: **STANDARD**, **PREMIUM** or **VIP**,
  - the price per seat in that section (one price for the whole section).

SeatSync expands each section into a full seat map automatically. Rows are
labelled 1, 2, 3, … up to the row count, and each seat gets an ID of the form
`SECTION-ROW-NUMBER` (seat B-2-7 is section B, row 2, seat 7). An event's
advertised price range ("from X to Y") is derived automatically from its
cheapest and most expensive sections.

## The seat map is fixed after creation

Choose your layout carefully: **the seat map cannot be changed once the event
is created.** After creation you can still edit the event's metadata — name,
description, category, venue, start and end times — but not sections, rows,
seat counts or per-section prices. If the layout is wrong, cancel the event
and create a new one before tickets are sold.

## Publishing

Draft events are invisible to attendees and do not appear in search. When you
**publish** an event it immediately becomes searchable and bookable. Publishing
is one-way: to take a published event off sale you cancel it.

## Cancelling an event

Cancelling is a soft operation: the event stays in your dashboard marked
CANCELLED, sales stop immediately, every confirmed attendee is notified by
email and refunded **100% regardless of how close to the event the
cancellation happens** (see the Refund and Cancellation Policy).

## Sales, stats and revenue

Your dashboard shows, per event and live: total seats, currently available
seats, seats under an active hold, booked seats, and gross revenue from
confirmed bookings. Remember that holds are not sales — a held seat has not
been paid for and may return to the pool after its 5-minute hold expires.

## How attendees buy — what organizers should know

Attendees hold a seat for 5 minutes, then confirm to pay. Freed seats
(expired holds, cancellations) are automatically offered to the event's
waitlist, oldest entry first, with a 10-minute offer window per person.
Confirmation and cancellation emails are sent by SeatSync automatically; you
do not need to email attendees yourself.

## Payouts

Revenue from confirmed bookings, minus any refunds paid out under the refund
policy, is transferred to the bank account registered on your organizer
profile within **7 business days after the event ends**. Cancelled events
produce no payout, since all bookings are refunded in full.

## Getting help

Organizer support is handled by the same team as attendee support: email
**support@seatsync.local** and include your organizer account email and, when
relevant, the event name.

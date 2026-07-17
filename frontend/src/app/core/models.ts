// Typed interfaces matching docs/CONVENTIONS.md §6 exactly.
// IDs are UUID strings, timestamps ISO-8601 UTC instants, money JSON numbers.

export type Role = 'ATTENDEE' | 'ORGANIZER' | 'ADMIN';

export interface UserProfile {
  id: string;
  email: string;
  fullName: string;
  roles: Role[];
}

/** §6.1 POST /api/auth/login | /api/auth/refresh response */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: 'Bearer';
  expiresInSeconds: number;
  user: UserProfile;
}

export interface RegisterRequest {
  email: string;
  password: string;
  fullName: string;
  role: 'ATTENDEE' | 'ORGANIZER';
}

// ---------------------------------------------------------------- catalog §6.2

export interface Venue {
  id: string;
  name: string;
  city: string;
  address: string;
}

export type EventCategory = 'CONCERT' | 'WORKSHOP' | 'SPORTS' | 'CAMPUS' | 'OTHER';
export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'CANCELLED';
export type PriceTier = 'STANDARD' | 'PREMIUM' | 'VIP';

export interface SeatSyncEvent {
  id: string;
  name: string;
  description: string;
  category: EventCategory;
  venue: Venue;
  startsAt: string;
  endsAt: string;
  status: EventStatus;
  organizerId: string;
  totalSeats: number;
  priceFrom: number;
  priceTo: number;
}

/** Spring Page shape for GET /api/catalog/events */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface SeatMapSeat {
  seatId: string; // "<section>-<row>-<number>", e.g. "A-1-1"
  number: number;
  price: number;
}

export interface SeatMapRow {
  label: string;
  seats: SeatMapSeat[];
}

export interface SeatMapSection {
  name: string;
  priceTier: PriceTier;
  price: number;
  rows: SeatMapRow[];
}

/** GET /api/catalog/events/{id}/seatmap */
export interface SeatMap {
  eventId: string;
  sections: SeatMapSection[];
}

export interface SectionSpec {
  name: string;
  rowCount: number;
  seatsPerRow: number;
  priceTier: PriceTier;
  price: number;
}

/** POST /api/catalog/events body */
export interface CreateEventRequest {
  name: string;
  description: string;
  category: EventCategory;
  venueId: string;
  startsAt: string;
  endsAt: string;
  sections: SectionSpec[];
}

export interface UpdateEventRequest {
  name: string;
  description: string;
  category: EventCategory;
  venueId: string;
  startsAt: string;
  endsAt: string;
}

export interface CreateVenueRequest {
  name: string;
  city: string;
  address: string;
}

// ---------------------------------------------------------------- booking §6.3

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';

/** Element of GET /api/events/{eventId}/seats */
export interface SeatState {
  seatId: string;
  status: SeatStatus;
  mine: boolean;
  holdExpiresAt?: string;
}

export interface EventSeats {
  eventId: string;
  seats: SeatState[];
}

/** POST /api/holds → 201 */
export interface Hold {
  holdId: string;
  eventId: string;
  seatId: string;
  userId: string;
  expiresAt: string;
  status: 'HELD';
}

/** GET /api/holds/mine element */
export interface MyHold {
  holdId: string;
  eventId: string;
  seatId: string;
  expiresAt: string;
}

/** POST /api/bookings → 201 and GET /api/bookings/mine element */
export interface Booking {
  bookingId: string;
  eventId: string;
  eventName: string;
  seatId: string;
  price: number;
  status: 'CONFIRMED' | 'CANCELLED';
  confirmedAt: string;
}

/** GET /api/events/{eventId}/stats */
export interface EventStats {
  eventId: string;
  totalSeats: number;
  available: number;
  held: number;
  booked: number;
  revenue: number;
}

/** POST /api/events/{eventId}/waitlist → 201 */
export interface WaitlistResponse {
  position: number;
}

/** STOMP message on /topic/events/{eventId}/seats */
export interface SeatEventMessage {
  eventId: string;
  seatId: string;
  status: SeatStatus;
  at: string;
}

// -------------------------------------------------------------- concierge §6.6

export interface ConciergeSource {
  title: string;
  snippet: string;
  score: number;
}

export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW';

/** POST /api/concierge/chat → 200 */
export interface ConciergeResponse {
  conversationId: string;
  answer: string;
  sources: ConciergeSource[];
  confidence: Confidence;
  escalatedToHuman: boolean;
}

// ---------------------------------------------------------------------- errors

/** RFC 7807 ProblemDetail (§5) */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
}

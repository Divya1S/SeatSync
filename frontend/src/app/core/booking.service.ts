import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Booking, EventSeats, EventStats, Hold, MyHold, WaitlistResponse } from './models';

/**
 * Booking-service endpoints. Note: these live under /api/holds, /api/bookings
 * and /api/events/* (NOT /api/catalog) per CONVENTIONS §6.3.
 */
@Injectable({ providedIn: 'root' })
export class BookingService {
  private readonly http = inject(HttpClient);

  getEventSeats(eventId: string): Observable<EventSeats> {
    return this.http.get<EventSeats>(`/api/events/${eventId}/seats`);
  }

  getEventStats(eventId: string): Observable<EventStats> {
    return this.http.get<EventStats>(`/api/events/${eventId}/stats`);
  }

  holdSeat(eventId: string, seatId: string): Observable<Hold> {
    return this.http.post<Hold>('/api/holds', { eventId, seatId });
  }

  releaseHold(holdId: string): Observable<void> {
    return this.http.delete<void>(`/api/holds/${holdId}`);
  }

  getMyHolds(): Observable<MyHold[]> {
    return this.http.get<MyHold[]>('/api/holds/mine');
  }

  confirmBooking(holdId: string): Observable<Booking> {
    return this.http.post<Booking>('/api/bookings', { holdId });
  }

  getMyBookings(): Observable<Booking[]> {
    return this.http.get<Booking[]>('/api/bookings/mine');
  }

  cancelBooking(bookingId: string): Observable<void> {
    return this.http.delete<void>(`/api/bookings/${bookingId}`);
  }

  joinWaitlist(eventId: string): Observable<WaitlistResponse> {
    return this.http.post<WaitlistResponse>(`/api/events/${eventId}/waitlist`, {});
  }

  leaveWaitlist(eventId: string): Observable<void> {
    return this.http.delete<void>(`/api/events/${eventId}/waitlist`);
  }
}

import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  CreateEventRequest,
  CreateVenueRequest,
  Page,
  SeatMap,
  SeatSyncEvent,
  UpdateEventRequest,
  Venue,
} from './models';

export interface EventSearch {
  q?: string;
  category?: string;
  city?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  searchEvents(search: EventSearch): Observable<Page<SeatSyncEvent>> {
    let params = new HttpParams()
      .set('page', search.page ?? 0)
      .set('size', search.size ?? 20);
    for (const key of ['q', 'category', 'city', 'from', 'to'] as const) {
      const value = search[key];
      if (value) {
        params = params.set(key, value);
      }
    }
    return this.http.get<Page<SeatSyncEvent>>('/api/catalog/events', { params });
  }

  getEvent(id: string): Observable<SeatSyncEvent> {
    return this.http.get<SeatSyncEvent>(`/api/catalog/events/${id}`);
  }

  getSeatMap(id: string): Observable<SeatMap> {
    return this.http.get<SeatMap>(`/api/catalog/events/${id}/seatmap`);
  }

  getMyEvents(): Observable<SeatSyncEvent[]> {
    return this.http.get<SeatSyncEvent[]>('/api/catalog/events/mine');
  }

  createEvent(request: CreateEventRequest): Observable<SeatSyncEvent> {
    return this.http.post<SeatSyncEvent>('/api/catalog/events', request);
  }

  updateEvent(id: string, request: UpdateEventRequest): Observable<SeatSyncEvent> {
    return this.http.put<SeatSyncEvent>(`/api/catalog/events/${id}`, request);
  }

  publishEvent(id: string): Observable<SeatSyncEvent> {
    return this.http.post<SeatSyncEvent>(`/api/catalog/events/${id}/publish`, {});
  }

  cancelEvent(id: string): Observable<void> {
    return this.http.delete<void>(`/api/catalog/events/${id}`);
  }

  getVenues(): Observable<Venue[]> {
    return this.http.get<Venue[]>('/api/catalog/venues');
  }

  createVenue(request: CreateVenueRequest): Observable<Venue> {
    return this.http.post<Venue>('/api/catalog/venues', request);
  }
}

import { Component, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subscription } from 'rxjs';
import { CatalogService } from '../../core/catalog.service';
import { BookingService } from '../../core/booking.service';
import { SeatSocketService } from '../../core/seat-socket.service';
import { EventStats, SeatSyncEvent } from '../../core/models';
import { EventForm } from './event-form';

const STATS_POLL_MS = 10_000;

@Component({
  selector: 'app-organizer',
  imports: [
    CurrencyPipe,
    DatePipe,
    RouterLink,
    MatCardModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
    EventForm,
  ],
  template: `
    <div class="header-row">
      <h1>Organizer dashboard</h1>
      <button mat-flat-button (click)="startCreate()">
        <mat-icon>add</mat-icon>
        New event
      </button>
    </div>

    @if (formOpen()) {
      <app-event-form
        [event]="editingEvent()"
        (saved)="onSaved($event)"
        (cancelled)="closeForm()"
      />
    }

    @if (loading()) {
      <div class="center"><mat-spinner diameter="48" /></div>
    } @else if (events().length === 0 && !formOpen()) {
      <mat-card appearance="outlined">
        <mat-card-content>You have no events yet — create your first one.</mat-card-content>
      </mat-card>
    } @else {
      <table mat-table [dataSource]="events()" class="events-table">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Event</th>
          <td mat-cell *matCellDef="let e">
            <a [routerLink]="['/events', e.id]">{{ e.name }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="venue">
          <th mat-header-cell *matHeaderCellDef>Venue</th>
          <td mat-cell *matCellDef="let e">{{ e.venue.name }} · {{ e.venue.city }}</td>
        </ng-container>
        <ng-container matColumnDef="startsAt">
          <th mat-header-cell *matHeaderCellDef>Starts</th>
          <td mat-cell *matCellDef="let e">{{ e.startsAt | date: 'MMM d, y, HH:mm' }}</td>
        </ng-container>
        <ng-container matColumnDef="status">
          <th mat-header-cell *matHeaderCellDef>Status</th>
          <td mat-cell *matCellDef="let e">
            <mat-chip-set>
              <mat-chip [class]="'status-' + e.status.toLowerCase()">{{ e.status }}</mat-chip>
            </mat-chip-set>
          </td>
        </ng-container>
        <ng-container matColumnDef="seats">
          <th mat-header-cell *matHeaderCellDef>Seats</th>
          <td mat-cell *matCellDef="let e">{{ e.totalSeats }}</td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let e" class="actions-cell">
            @if (e.status === 'DRAFT') {
              <button mat-flat-button (click)="publish(e)">
                <mat-icon>publish</mat-icon>
                Publish
              </button>
            }
            <button mat-stroked-button (click)="startEdit(e)">Edit</button>
            @if (e.status === 'PUBLISHED') {
              <button
                mat-stroked-button
                (click)="toggleSales(e)"
                [class.active-sales]="salesEvent()?.id === e.id"
              >
                <mat-icon>monitoring</mat-icon>
                Sales
              </button>
            }
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
    }

    @if (salesEvent(); as ev) {
      <section class="sales-section">
        <h2>Live sales — {{ ev.name }}</h2>
        @if (stats(); as s) {
          <div class="stat-cards">
            <mat-card class="stat-card">
              <div class="stat-value">{{ s.available }}</div>
              <div class="stat-label">Available</div>
            </mat-card>
            <mat-card class="stat-card">
              <div class="stat-value">{{ s.held }}</div>
              <div class="stat-label">Held</div>
            </mat-card>
            <mat-card class="stat-card">
              <div class="stat-value">{{ s.booked }}</div>
              <div class="stat-label">Booked</div>
            </mat-card>
            <mat-card class="stat-card">
              <div class="stat-value">{{ s.revenue | currency: 'USD' }}</div>
              <div class="stat-label">Revenue</div>
            </mat-card>
          </div>
          <p class="hint">
            {{ s.booked }}/{{ s.totalSeats }} seats sold · refreshes every 10s + live updates
          </p>
        } @else {
          <mat-spinner diameter="32" />
        }
      </section>
    }
  `,
  styles: `
    .header-row { display: flex; justify-content: space-between; align-items: center; }
    .events-table { width: 100%; margin-top: 16px; }
    .actions-cell { display: flex; gap: 8px; padding: 8px 0; flex-wrap: wrap; }
    mat-chip.status-draft { opacity: 0.7; }
    mat-chip.status-cancelled { text-decoration: line-through; opacity: 0.6; }
    .active-sales { border-color: var(--mat-sys-primary); }
    .sales-section { margin-top: 32px; }
    .stat-cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 16px;
    }
    .stat-card { text-align: center; padding: 20px 8px; }
    .stat-value { font-size: 2rem; font-weight: 700; }
    .stat-label { opacity: 0.7; margin-top: 4px; }
    .hint { opacity: 0.6; margin-top: 12px; }
    .center { display: flex; justify-content: center; padding: 48px; }
  `,
})
export class Organizer {
  private readonly catalog = inject(CatalogService);
  private readonly booking = inject(BookingService);
  private readonly socket = inject(SeatSocketService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly columns = ['name', 'venue', 'startsAt', 'status', 'seats', 'actions'];

  protected readonly events = signal<SeatSyncEvent[]>([]);
  protected readonly loading = signal(true);
  protected readonly formOpen = signal(false);
  protected readonly editingEvent = signal<SeatSyncEvent | null>(null);
  protected readonly salesEvent = signal<SeatSyncEvent | null>(null);
  protected readonly stats = signal<EventStats | null>(null);

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private socketSub: Subscription | null = null;

  constructor() {
    this.load();

    // Poll stats every 10s and refresh instantly on live seat updates (STOMP).
    effect(() => {
      const event = this.salesEvent();
      untracked(() => this.wireSales(event));
    });

    this.destroyRef.onDestroy(() => this.stopSales());
  }

  protected startCreate(): void {
    this.editingEvent.set(null);
    this.formOpen.set(true);
  }

  protected startEdit(event: SeatSyncEvent): void {
    this.editingEvent.set(event);
    this.formOpen.set(true);
  }

  protected closeForm(): void {
    this.formOpen.set(false);
    this.editingEvent.set(null);
  }

  protected onSaved(event: SeatSyncEvent): void {
    this.closeForm();
    this.snackBar.open(`Event "${event.name}" saved.`, 'OK', { duration: 4000 });
    this.load();
  }

  protected publish(event: SeatSyncEvent): void {
    this.catalog.publishEvent(event.id).subscribe({
      next: () => {
        this.snackBar.open(`"${event.name}" is now live.`, 'OK', { duration: 4000 });
        this.load();
      },
      error: () => this.snackBar.open('Publish failed.', 'OK', { duration: 4000 }),
    });
  }

  protected toggleSales(event: SeatSyncEvent): void {
    this.salesEvent.set(this.salesEvent()?.id === event.id ? null : event);
  }

  private load(): void {
    this.loading.set(true);
    this.catalog.getMyEvents().subscribe({
      next: (events) => {
        this.events.set(events);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.snackBar.open('Could not load your events.', 'OK', { duration: 4000 });
      },
    });
  }

  private wireSales(event: SeatSyncEvent | null): void {
    this.stopSales();
    this.stats.set(null);
    if (!event) {
      return;
    }
    this.fetchStats(event.id);
    this.pollHandle = setInterval(() => this.fetchStats(event.id), STATS_POLL_MS);
    this.socketSub = this.socket.seatUpdates(event.id).subscribe(() => this.fetchStats(event.id));
  }

  private stopSales(): void {
    if (this.pollHandle !== null) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
    this.socketSub?.unsubscribe();
    this.socketSub = null;
  }

  private fetchStats(eventId: string): void {
    this.booking.getEventStats(eventId).subscribe({
      next: (stats) => this.stats.set(stats),
      error: () => undefined,
    });
  }
}

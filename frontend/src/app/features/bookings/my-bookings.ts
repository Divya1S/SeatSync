import { Component, computed, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { BookingService } from '../../core/booking.service';
import { CountdownService, formatCountdown, isExpired } from '../../core/countdown';
import { Booking, MyHold } from '../../core/models';

@Component({
  selector: 'app-my-bookings',
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
  ],
  template: `
    <h1>My bookings</h1>

    @if (activeHolds().length > 0) {
      <mat-card class="holds-card" appearance="outlined">
        <mat-card-header>
          <mat-card-title>Active holds</mat-card-title>
          <mat-card-subtitle>Confirm these before they expire</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          @for (hold of activeHolds(); track hold.holdId) {
            <div class="hold-row">
              <mat-icon>schedule</mat-icon>
              <span class="seat-id">Seat {{ hold.seatId }}</span>
              <span class="countdown">{{ countdown(hold.expiresAt) }}</span>
              <a mat-stroked-button [routerLink]="['/events', hold.eventId]">Open event</a>
              <button mat-button (click)="releaseHold(hold)">Release</button>
            </div>
          }
        </mat-card-content>
      </mat-card>
    }

    @if (loading()) {
      <div class="center"><mat-spinner diameter="48" /></div>
    } @else if (bookings().length === 0) {
      <mat-card appearance="outlined">
        <mat-card-content>
          You have no bookings yet. <a routerLink="/events">Browse events</a> to grab a seat.
        </mat-card-content>
      </mat-card>
    } @else {
      <table mat-table [dataSource]="bookings()" class="bookings-table">
        <ng-container matColumnDef="eventName">
          <th mat-header-cell *matHeaderCellDef>Event</th>
          <td mat-cell *matCellDef="let b">
            <a [routerLink]="['/events', b.eventId]">{{ b.eventName }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="seatId">
          <th mat-header-cell *matHeaderCellDef>Seat</th>
          <td mat-cell *matCellDef="let b">{{ b.seatId }}</td>
        </ng-container>
        <ng-container matColumnDef="price">
          <th mat-header-cell *matHeaderCellDef>Price</th>
          <td mat-cell *matCellDef="let b">{{ b.price | currency: 'USD' }}</td>
        </ng-container>
        <ng-container matColumnDef="status">
          <th mat-header-cell *matHeaderCellDef>Status</th>
          <td mat-cell *matCellDef="let b">
            <mat-chip-set>
              <mat-chip [class.cancelled]="b.status === 'CANCELLED'">{{ b.status }}</mat-chip>
            </mat-chip-set>
          </td>
        </ng-container>
        <ng-container matColumnDef="confirmedAt">
          <th mat-header-cell *matHeaderCellDef>Booked on</th>
          <td mat-cell *matCellDef="let b">{{ b.confirmedAt | date: 'MMM d, y, HH:mm' }}</td>
        </ng-container>
        <ng-container matColumnDef="actions">
          <th mat-header-cell *matHeaderCellDef></th>
          <td mat-cell *matCellDef="let b">
            @if (b.status === 'CONFIRMED') {
              <button mat-stroked-button (click)="cancel(b)">Cancel</button>
            }
          </td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
    }
  `,
  styles: `
    .holds-card { margin-bottom: 24px; }
    .hold-row { display: flex; align-items: center; gap: 16px; padding: 8px 0; flex-wrap: wrap; }
    .seat-id { font-weight: 600; }
    .countdown { font-variant-numeric: tabular-nums; font-weight: 700; font-size: 1.1rem; }
    .bookings-table { width: 100%; }
    mat-chip.cancelled { opacity: 0.6; }
    .center { display: flex; justify-content: center; padding: 48px; }
  `,
})
export class MyBookings {
  private readonly booking = inject(BookingService);
  private readonly ticker = inject(CountdownService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly columns = ['eventName', 'seatId', 'price', 'status', 'confirmedAt', 'actions'];

  protected readonly bookings = signal<Booking[]>([]);
  protected readonly holds = signal<MyHold[]>([]);
  protected readonly loading = signal(true);

  protected readonly activeHolds = computed(() =>
    this.holds().filter((h) => !isExpired(h.expiresAt, this.ticker.now())),
  );

  constructor() {
    this.load();
  }

  protected countdown(expiresAt: string): string {
    return formatCountdown(expiresAt, this.ticker.now());
  }

  protected cancel(booking: Booking): void {
    this.booking.cancelBooking(booking.bookingId).subscribe({
      next: () => {
        this.snackBar.open(`Booking for seat ${booking.seatId} cancelled.`, 'OK', {
          duration: 4000,
        });
        this.load();
      },
      error: () =>
        this.snackBar.open('Could not cancel that booking.', 'OK', { duration: 4000 }),
    });
  }

  protected releaseHold(hold: MyHold): void {
    this.booking.releaseHold(hold.holdId).subscribe({
      next: () => this.holds.update((hs) => hs.filter((h) => h.holdId !== hold.holdId)),
      error: () => this.load(),
    });
  }

  private load(): void {
    this.loading.set(true);
    this.booking.getMyBookings().subscribe({
      next: (bookings) => {
        this.bookings.set(bookings);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
    this.booking.getMyHolds().subscribe({
      next: (holds) => this.holds.set(holds),
      error: () => undefined,
    });
  }
}

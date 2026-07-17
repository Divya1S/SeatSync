import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subscription } from 'rxjs';
import { CatalogService } from '../../core/catalog.service';
import { BookingService } from '../../core/booking.service';
import { AuthService } from '../../core/auth.service';
import { SeatSocketService } from '../../core/seat-socket.service';
import { CountdownService, formatCountdown, isExpired } from '../../core/countdown';
import { Hold, ProblemDetail, SeatMap, SeatState, SeatSyncEvent } from '../../core/models';
import { SeatView, applySeatUpdate, buildSectionViews } from '../../core/seat-state';

@Component({
  selector: 'app-event-detail',
  imports: [
    CurrencyPipe,
    DatePipe,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatSnackBarModule,
    MatProgressSpinnerModule,
  ],
  template: `
    @if (event(); as ev) {
      <header class="event-header">
        <div>
          <h1>{{ ev.name }}</h1>
          <p class="sub">
            <mat-icon inline>place</mat-icon> {{ ev.venue.name }}, {{ ev.venue.address }},
            {{ ev.venue.city }}
          </p>
          <p class="sub">
            <mat-icon inline>calendar_today</mat-icon>
            {{ ev.startsAt | date: 'EEE, MMM d, y, HH:mm' }} –
            {{ ev.endsAt | date: 'HH:mm' }}
          </p>
          <p>{{ ev.description }}</p>
        </div>
        <div class="header-side">
          <mat-chip-set>
            <mat-chip>{{ ev.category }}</mat-chip>
          </mat-chip-set>
          <div class="price-range">
            {{ ev.priceFrom | currency: 'USD' }} – {{ ev.priceTo | currency: 'USD' }}
          </div>
        </div>
      </header>
    }

    <div class="detail-layout">
      <section class="seatmap-panel">
        <div class="legend">
          <span><i class="dot available"></i> Available</span>
          <span><i class="dot held-mine"></i> Your hold</span>
          <span><i class="dot held-other"></i> Held</span>
          <span><i class="dot booked"></i> Booked</span>
        </div>

        @if (loading()) {
          <div class="center"><mat-spinner diameter="48" /></div>
        } @else {
          @for (section of sections(); track section.name) {
            <mat-card appearance="outlined" class="section-card">
              <mat-card-header>
                <mat-card-title>Section {{ section.name }}</mat-card-title>
                <mat-card-subtitle>
                  {{ section.priceTier }} · {{ section.price | currency: 'USD' }}
                </mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                @for (row of section.rows; track row.label) {
                  <div class="seat-row">
                    <span class="row-label">{{ row.label }}</span>
                    @for (seat of row.seats; track seat.seatId) {
                      <button
                        class="seat"
                        [class]="'seat ' + seat.ui"
                        [disabled]="seat.ui === 'held-other' || seat.ui === 'booked'"
                        [title]="seat.seatId + ' · $' + seat.price"
                        (click)="onSeatClick(seat)"
                      >
                        {{ seat.number }}
                      </button>
                    }
                  </div>
                }
              </mat-card-content>
            </mat-card>
          }
        }
      </section>

      <aside class="side-panel">
        @if (myHold(); as hold) {
          <mat-card class="hold-card">
            <mat-card-header>
              <mat-card-title>Your hold</mat-card-title>
              <mat-card-subtitle>Seat {{ hold.seatId }}</mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <div class="countdown">{{ holdCountdown() }}</div>
              <p class="hint">Confirm before the timer runs out or the seat is released.</p>
            </mat-card-content>
            <mat-card-actions>
              <button mat-flat-button (click)="confirm()" [disabled]="acting()">
                <mat-icon>check_circle</mat-icon>
                Confirm booking
              </button>
              <button mat-stroked-button (click)="release()" [disabled]="acting()">
                Release
              </button>
            </mat-card-actions>
          </mat-card>
        } @else if (soldOut()) {
          <mat-card class="hold-card">
            <mat-card-header>
              <mat-card-title>Sold out</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              @if (waitlistPosition(); as pos) {
                <p>You are on the waitlist at position {{ pos }}.</p>
              } @else {
                <p>All seats are booked. Join the waitlist to be offered the next free seat.</p>
              }
            </mat-card-content>
            @if (!waitlistPosition()) {
              <mat-card-actions>
                <button mat-flat-button (click)="joinWaitlist()" [disabled]="acting()">
                  <mat-icon>hourglass_top</mat-icon>
                  Join waitlist
                </button>
              </mat-card-actions>
            }
          </mat-card>
        } @else {
          <mat-card appearance="outlined" class="hold-card">
            <mat-card-content>
              <p class="hint">
                Pick an available seat to place a 5-minute hold, then confirm your booking.
              </p>
            </mat-card-content>
          </mat-card>
        }
      </aside>
    </div>
  `,
  styles: `
    .event-header { display: flex; justify-content: space-between; gap: 24px; flex-wrap: wrap; }
    .event-header h1 { margin-bottom: 4px; }
    .sub { display: flex; align-items: center; gap: 6px; opacity: 0.8; margin: 2px 0; }
    .header-side { text-align: right; }
    .price-range { font-size: 1.2rem; font-weight: 600; margin-top: 8px; }
    .detail-layout { display: flex; gap: 24px; align-items: flex-start; flex-wrap: wrap; }
    .seatmap-panel { flex: 1 1 560px; min-width: 0; }
    .side-panel { flex: 0 0 280px; position: sticky; top: 88px; }
    .legend { display: flex; gap: 16px; flex-wrap: wrap; margin: 12px 0; font-size: 0.85rem; }
    .legend .dot {
      display: inline-block; width: 12px; height: 12px; border-radius: 3px;
      margin-right: 4px; vertical-align: -1px;
    }
    .section-card { margin-bottom: 16px; overflow-x: auto; }
    .seat-row { display: flex; gap: 6px; align-items: center; margin: 6px 0; }
    .row-label { width: 24px; opacity: 0.6; font-size: 0.8rem; text-align: right; }
    .seat {
      width: 34px; height: 30px; border-radius: 6px; font-size: 0.75rem;
      display: inline-flex; align-items: center; justify-content: center;
      border: 1px solid transparent; cursor: pointer; padding: 0;
    }
    .seat.available, .dot.available {
      background: transparent; border-color: var(--mat-sys-primary); color: var(--mat-sys-primary);
    }
    .seat.available:hover { background: var(--mat-sys-primary-container); }
    .seat.held-mine, .dot.held-mine {
      background: var(--mat-sys-tertiary); border-color: var(--mat-sys-tertiary);
      color: var(--mat-sys-on-tertiary); font-weight: 700;
    }
    .seat.held-other, .dot.held-other {
      background: var(--mat-sys-surface-variant); border-color: var(--mat-sys-outline-variant);
      color: var(--mat-sys-outline); cursor: not-allowed;
    }
    .seat.booked, .dot.booked {
      background: var(--mat-sys-inverse-surface); border-color: var(--mat-sys-inverse-surface);
      color: var(--mat-sys-inverse-on-surface); cursor: not-allowed; opacity: 0.9;
    }
    .hold-card { min-width: 260px; }
    .countdown { font-size: 2.4rem; font-weight: 700; text-align: center; letter-spacing: 2px; }
    .hint { opacity: 0.75; }
    .center { display: flex; justify-content: center; padding: 48px; }
    mat-card-actions { display: flex; gap: 8px; }
  `,
})
export class EventDetail {
  private readonly catalog = inject(CatalogService);
  private readonly booking = inject(BookingService);
  private readonly auth = inject(AuthService);
  private readonly socket = inject(SeatSocketService);
  private readonly ticker = inject(CountdownService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  /** Route param via withComponentInputBinding(). */
  readonly id = input.required<string>();

  protected readonly event = signal<SeatSyncEvent | null>(null);
  protected readonly seatMap = signal<SeatMap | null>(null);
  protected readonly seatStates = signal<ReadonlyMap<string, SeatState>>(new Map());
  protected readonly myHold = signal<Hold | null>(null);
  protected readonly waitlistPosition = signal<number | null>(null);
  protected readonly loading = signal(true);
  protected readonly acting = signal(false);

  private socketSub: Subscription | null = null;

  protected readonly sections = computed(() => {
    const map = this.seatMap();
    return map ? buildSectionViews(map, this.seatStates()) : [];
  });

  protected readonly soldOut = computed(() => {
    const states = this.seatStates();
    if (states.size === 0) {
      return false;
    }
    for (const state of states.values()) {
      if (state.status !== 'BOOKED') {
        return false;
      }
    }
    return true;
  });

  protected readonly holdCountdown = computed(() => {
    const hold = this.myHold();
    return hold ? formatCountdown(hold.expiresAt, this.ticker.now()) : '';
  });

  constructor() {
    effect(() => {
      const eventId = this.id();
      untracked(() => this.loadAll(eventId));
    });

    // When the local hold countdown reaches zero, drop it and refresh seats.
    effect(() => {
      const hold = this.myHold();
      if (hold && isExpired(hold.expiresAt, this.ticker.now())) {
        this.myHold.set(null);
        untracked(() => this.refreshSeats(this.id()));
        this.snackBar.open('Your hold expired.', 'OK', { duration: 4000 });
      }
    });

    this.destroyRef.onDestroy(() => this.socketSub?.unsubscribe());
  }

  protected onSeatClick(seat: SeatView): void {
    if (seat.ui !== 'available') {
      return;
    }
    if (!this.auth.isLoggedIn()) {
      this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.url } });
      return;
    }
    if (this.myHold()) {
      this.snackBar.open('Release or confirm your current hold first.', 'OK', { duration: 3500 });
      return;
    }
    this.acting.set(true);
    this.booking.holdSeat(this.id(), seat.seatId).subscribe({
      next: (hold) => {
        this.myHold.set(hold);
        this.setSeatState(hold.seatId, 'HELD', true, hold.expiresAt);
        this.acting.set(false);
      },
      error: (err) => {
        this.acting.set(false);
        this.snackBar.open(problemDetailMessage(err, 'Could not hold that seat.'), 'OK', {
          duration: 4000,
        });
        this.refreshSeats(this.id());
      },
    });
  }

  protected confirm(): void {
    const hold = this.myHold();
    if (!hold) {
      return;
    }
    this.acting.set(true);
    this.booking.confirmBooking(hold.holdId).subscribe({
      next: (booking) => {
        this.myHold.set(null);
        this.setSeatState(booking.seatId, 'BOOKED', false);
        this.acting.set(false);
        this.snackBar.open(`Booked seat ${booking.seatId}. See you there!`, 'OK', {
          duration: 5000,
        });
      },
      error: (err) => {
        this.acting.set(false);
        this.snackBar.open(problemDetailMessage(err, 'Booking failed.'), 'OK', { duration: 4000 });
        this.myHold.set(null);
        this.refreshSeats(this.id());
      },
    });
  }

  protected release(): void {
    const hold = this.myHold();
    if (!hold) {
      return;
    }
    this.acting.set(true);
    this.booking.releaseHold(hold.holdId).subscribe({
      next: () => {
        this.myHold.set(null);
        this.setSeatState(hold.seatId, 'AVAILABLE', false);
        this.acting.set(false);
      },
      error: () => {
        this.acting.set(false);
        this.myHold.set(null);
        this.refreshSeats(this.id());
      },
    });
  }

  protected joinWaitlist(): void {
    if (!this.auth.isLoggedIn()) {
      this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.url } });
      return;
    }
    this.acting.set(true);
    this.booking.joinWaitlist(this.id()).subscribe({
      next: (res) => {
        this.waitlistPosition.set(res.position);
        this.acting.set(false);
        this.snackBar.open(`You joined the waitlist (position ${res.position}).`, 'OK', {
          duration: 5000,
        });
      },
      error: (err) => {
        this.acting.set(false);
        this.snackBar.open(problemDetailMessage(err, 'Could not join the waitlist.'), 'OK', {
          duration: 4000,
        });
      },
    });
  }

  private loadAll(eventId: string): void {
    this.loading.set(true);
    this.event.set(null);
    this.seatMap.set(null);
    this.seatStates.set(new Map());
    this.myHold.set(null);
    this.waitlistPosition.set(null);

    this.catalog.getEvent(eventId).subscribe({
      next: (ev) => this.event.set(ev),
      error: () => this.snackBar.open('Event not found.', 'OK', { duration: 4000 }),
    });
    this.catalog.getSeatMap(eventId).subscribe({
      next: (map) => {
        this.seatMap.set(map);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
    this.refreshSeats(eventId);
    if (this.auth.isLoggedIn()) {
      this.booking.getMyHolds().subscribe({
        next: (holds) => {
          const active = holds.find(
            (h) => h.eventId === eventId && !isExpired(h.expiresAt, Date.now()),
          );
          if (active) {
            this.myHold.set({ ...active, userId: '', status: 'HELD' });
          }
        },
        error: () => undefined,
      });
    }

    this.socketSub?.unsubscribe();
    this.socketSub = this.socket
      .seatUpdates(eventId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((update) => {
        const hold = this.myHold();
        const mySeats = new Set(hold ? [hold.seatId] : []);
        this.seatStates.update((states) => applySeatUpdate(states, update, mySeats));
        // If someone else's action removed my hold (expiry sweep / booking), reflect it.
        if (hold && update.seatId === hold.seatId && update.status !== 'HELD') {
          this.myHold.set(null);
        }
      });
  }

  private refreshSeats(eventId: string): void {
    this.booking.getEventSeats(eventId).subscribe({
      next: (res) => {
        this.seatStates.set(new Map(res.seats.map((s) => [s.seatId, s])));
        const mine = res.seats.find((s) => s.mine && s.status === 'HELD');
        if (mine?.holdExpiresAt && this.myHold() === null) {
          // Seat view knows it's ours but we lack the holdId; /api/holds/mine fills it in.
          this.booking.getMyHolds().subscribe({
            next: (holds) => {
              const match = holds.find((h) => h.eventId === eventId && h.seatId === mine.seatId);
              if (match) {
                this.myHold.set({ ...match, userId: '', status: 'HELD' });
              }
            },
            error: () => undefined,
          });
        }
      },
      error: () => undefined,
    });
  }

  private setSeatState(
    seatId: string,
    status: SeatState['status'],
    mine: boolean,
    holdExpiresAt?: string,
  ): void {
    this.seatStates.update((states) => {
      const next = new Map(states);
      next.set(seatId, { seatId, status, mine, holdExpiresAt });
      return next;
    });
  }
}

function problemDetailMessage(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const problem = err.error as Partial<ProblemDetail> | null;
    if (problem && typeof problem.detail === 'string') {
      return problem.detail;
    }
  }
  return fallback;
}

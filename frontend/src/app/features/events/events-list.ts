import { Component, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { provideNativeDateAdapter } from '@angular/material/core';
import { CatalogService } from '../../core/catalog.service';
import { EventCategory, Page, SeatSyncEvent } from '../../core/models';

const CATEGORIES: EventCategory[] = ['CONCERT', 'WORKSHOP', 'SPORTS', 'CAMPUS', 'OTHER'];

@Component({
  selector: 'app-events-list',
  providers: [provideNativeDateAdapter()],
  imports: [
    CurrencyPipe,
    DatePipe,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatChipsModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    MatDatepickerModule,
  ],
  template: `
    <section class="filters">
      <mat-form-field appearance="outline" class="grow">
        <mat-label>Search events</mat-label>
        <input
          matInput
          [(ngModel)]="q"
          (keyup.enter)="applyFilters()"
          placeholder="e.g. jazz, workshop…"
        />
        <mat-icon matSuffix>search</mat-icon>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Category</mat-label>
        <mat-select [(ngModel)]="category" (selectionChange)="applyFilters()">
          <mat-option [value]="''">All</mat-option>
          @for (cat of categories; track cat) {
            <mat-option [value]="cat">{{ cat }}</mat-option>
          }
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>City</mat-label>
        <input matInput [(ngModel)]="city" (keyup.enter)="applyFilters()" />
      </mat-form-field>

      <mat-form-field appearance="outline">
        <mat-label>Date range</mat-label>
        <mat-date-range-input [rangePicker]="picker">
          <input matStartDate placeholder="From" [(ngModel)]="fromDate" />
          <input matEndDate placeholder="To" [(ngModel)]="toDate" (dateChange)="applyFilters()" />
        </mat-date-range-input>
        <mat-datepicker-toggle matIconSuffix [for]="picker" />
        <mat-date-range-picker #picker />
      </mat-form-field>

      <button mat-flat-button (click)="applyFilters()">Search</button>
    </section>

    @if (loading()) {
      <div class="center"><mat-spinner diameter="48" /></div>
    } @else if (error()) {
      <mat-card appearance="outlined" class="notice">
        <mat-card-content>
          Could not load events — is the backend running? ({{ error() }})
        </mat-card-content>
      </mat-card>
    } @else if (page(); as p) {
      @if (p.content.length === 0) {
        <mat-card appearance="outlined" class="notice">
          <mat-card-content>No events match your filters.</mat-card-content>
        </mat-card>
      }
      <section class="cards">
        @for (event of p.content; track event.id) {
          <mat-card appearance="outlined" class="event-card" [routerLink]="['/events', event.id]">
            <mat-card-header>
              <mat-card-title>{{ event.name }}</mat-card-title>
              <mat-card-subtitle>
                {{ event.venue.name }} · {{ event.venue.city }}
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <p class="date-line">
                <mat-icon inline>calendar_today</mat-icon>
                {{ event.startsAt | date: 'EEE, MMM d, y, HH:mm' }}
              </p>
              <p class="desc">{{ event.description }}</p>
              <div class="card-meta">
                <mat-chip-set>
                  <mat-chip>{{ event.category }}</mat-chip>
                </mat-chip-set>
                <span class="price">from {{ event.priceFrom | currency: 'USD' }}</span>
              </div>
            </mat-card-content>
          </mat-card>
        }
      </section>
      <mat-paginator
        [length]="p.totalElements"
        [pageIndex]="p.number"
        [pageSize]="p.size"
        [pageSizeOptions]="[12, 20, 40]"
        (page)="onPage($event)"
      />
    }
  `,
  styles: `
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: baseline;
      margin-bottom: 8px;
    }
    .filters .grow { flex: 1 1 240px; }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: 16px;
    }
    .event-card { cursor: pointer; }
    .event-card:hover { box-shadow: 0 4px 12px rgba(0, 0, 0, 0.18); }
    .date-line { display: flex; align-items: center; gap: 6px; opacity: 0.8; }
    .desc {
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      min-height: 2.6em;
    }
    .card-meta { display: flex; justify-content: space-between; align-items: center; }
    .price { font-weight: 600; }
    .center { display: flex; justify-content: center; padding: 48px; }
    .notice { margin: 24px 0; }
  `,
})
export class EventsList {
  private readonly catalog = inject(CatalogService);

  protected readonly categories = CATEGORIES;

  protected q = '';
  protected category = '';
  protected city = '';
  protected fromDate: Date | null = null;
  protected toDate: Date | null = null;

  protected readonly page = signal<Page<SeatSyncEvent> | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  private pageIndex = 0;
  private pageSize = 12;

  constructor() {
    this.load();
  }

  protected applyFilters(): void {
    this.pageIndex = 0;
    this.load();
  }

  protected onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.catalog
      .searchEvents({
        q: this.q.trim() || undefined,
        category: this.category || undefined,
        city: this.city.trim() || undefined,
        from: this.fromDate ? this.fromDate.toISOString() : undefined,
        to: this.toDate ? endOfDay(this.toDate).toISOString() : undefined,
        page: this.pageIndex,
        size: this.pageSize,
      })
      .subscribe({
        next: (page) => {
          this.page.set(page);
          this.loading.set(false);
        },
        error: (err) => {
          this.error.set(err?.status ? `HTTP ${err.status}` : 'network error');
          this.loading.set(false);
        },
      });
  }
}

function endOfDay(date: Date): Date {
  const d = new Date(date);
  d.setHours(23, 59, 59, 999);
  return d;
}

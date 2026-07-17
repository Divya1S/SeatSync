import { Component, effect, inject, input, output, signal } from '@angular/core';
import {
  FormArray,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { CatalogService } from '../../core/catalog.service';
import {
  EventCategory,
  PriceTier,
  SeatSyncEvent,
  SectionSpec,
  Venue,
} from '../../core/models';

const CATEGORIES: EventCategory[] = ['CONCERT', 'WORKSHOP', 'SPORTS', 'CAMPUS', 'OTHER'];
const PRICE_TIERS: PriceTier[] = ['STANDARD', 'PREMIUM', 'VIP'];

interface SectionFormValue {
  name: string;
  rowCount: number;
  seatsPerRow: number;
  priceTier: PriceTier;
  price: number;
}

@Component({
  selector: 'app-event-form',
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatSnackBarModule,
  ],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title>{{ editing() ? 'Edit event' : 'Create event' }}</mat-card-title>
        @if (editing()) {
          <mat-card-subtitle>Seat map cannot be changed after creation</mat-card-subtitle>
        }
      </mat-card-header>
      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="submit()" class="event-form">
          <mat-form-field appearance="outline" class="full">
            <mat-label>Name</mat-label>
            <input matInput formControlName="name" />
          </mat-form-field>

          <mat-form-field appearance="outline" class="full">
            <mat-label>Description</mat-label>
            <textarea matInput formControlName="description" rows="3"></textarea>
          </mat-form-field>

          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Category</mat-label>
              <mat-select formControlName="category">
                @for (cat of categories; track cat) {
                  <mat-option [value]="cat">{{ cat }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline" class="grow">
              <mat-label>Venue</mat-label>
              <mat-select formControlName="venueId">
                @for (venue of venues(); track venue.id) {
                  <mat-option [value]="venue.id">
                    {{ venue.name }} — {{ venue.city }}
                  </mat-option>
                }
              </mat-select>
            </mat-form-field>

            <button
              type="button"
              mat-stroked-button
              (click)="showVenueForm.set(!showVenueForm())"
            >
              <mat-icon>add_location_alt</mat-icon>
              New venue
            </button>
          </div>

          @if (showVenueForm()) {
            <div class="row venue-form" [formGroup]="venueForm">
              <mat-form-field appearance="outline">
                <mat-label>Venue name</mat-label>
                <input matInput formControlName="name" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>City</mat-label>
                <input matInput formControlName="city" />
              </mat-form-field>
              <mat-form-field appearance="outline" class="grow">
                <mat-label>Address</mat-label>
                <input matInput formControlName="address" />
              </mat-form-field>
              <button
                type="button"
                mat-flat-button
                [disabled]="venueForm.invalid || busy()"
                (click)="createVenue()"
              >
                Save venue
              </button>
            </div>
          }

          <div class="row">
            <mat-form-field appearance="outline">
              <mat-label>Starts at</mat-label>
              <input matInput type="datetime-local" formControlName="startsAt" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Ends at</mat-label>
              <input matInput type="datetime-local" formControlName="endsAt" />
            </mat-form-field>
          </div>

          @if (!editing()) {
            <h3>Sections</h3>
            <div formArrayName="sections">
              @for (section of sections.controls; track section; let i = $index) {
                <div class="row section-row" [formGroupName]="i">
                  <mat-form-field appearance="outline" class="narrow">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="name" placeholder="A" />
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="narrow">
                    <mat-label>Rows</mat-label>
                    <input matInput type="number" formControlName="rowCount" min="1" />
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="narrow">
                    <mat-label>Seats/row</mat-label>
                    <input matInput type="number" formControlName="seatsPerRow" min="1" />
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Tier</mat-label>
                    <mat-select formControlName="priceTier">
                      @for (tier of priceTiers; track tier) {
                        <mat-option [value]="tier">{{ tier }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field appearance="outline" class="narrow">
                    <mat-label>Price</mat-label>
                    <input matInput type="number" formControlName="price" min="0" step="0.01" />
                  </mat-form-field>
                  <button
                    type="button"
                    mat-icon-button
                    (click)="removeSection(i)"
                    [disabled]="sections.length <= 1"
                    title="Remove section"
                  >
                    <mat-icon>delete</mat-icon>
                  </button>
                </div>
              }
            </div>
            <button type="button" mat-stroked-button (click)="addSection()">
              <mat-icon>add</mat-icon>
              Add section
            </button>
          }

          <div class="actions">
            <button mat-flat-button type="submit" [disabled]="form.invalid || busy()">
              {{ editing() ? 'Save changes' : 'Create event' }}
            </button>
            <button mat-button type="button" (click)="cancelled.emit()">Cancel</button>
          </div>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .event-form { display: flex; flex-direction: column; gap: 4px; }
    .full { width: 100%; }
    .row { display: flex; gap: 12px; flex-wrap: wrap; align-items: baseline; }
    .grow { flex: 1 1 200px; }
    .narrow { width: 110px; }
    .venue-form {
      border: 1px dashed var(--mat-sys-outline-variant);
      border-radius: 8px; padding: 12px;
    }
    .section-row { align-items: baseline; }
    .actions { display: flex; gap: 8px; margin-top: 12px; }
  `,
})
export class EventForm {
  private readonly fb = inject(FormBuilder);
  private readonly catalog = inject(CatalogService);
  private readonly snackBar = inject(MatSnackBar);

  /** When set, the form edits this event's metadata (no seat map changes). */
  readonly event = input<SeatSyncEvent | null>(null);
  readonly saved = output<SeatSyncEvent>();
  readonly cancelled = output<void>();

  protected readonly categories = CATEGORIES;
  protected readonly priceTiers = PRICE_TIERS;
  protected readonly venues = signal<Venue[]>([]);
  protected readonly showVenueForm = signal(false);
  protected readonly busy = signal(false);
  protected readonly editing = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    name: ['', Validators.required],
    description: ['', Validators.required],
    category: ['CONCERT' as EventCategory, Validators.required],
    venueId: ['', Validators.required],
    startsAt: ['', Validators.required],
    endsAt: ['', Validators.required],
    sections: this.fb.array<FormGroup>([]),
  });

  protected readonly venueForm = this.fb.nonNullable.group({
    name: ['', Validators.required],
    city: ['', Validators.required],
    address: ['', Validators.required],
  });

  protected get sections(): FormArray<FormGroup> {
    return this.form.controls.sections;
  }

  constructor() {
    this.addSection();
    this.loadVenues();

    effect(() => {
      const event = this.event();
      this.editing.set(!!event);
      if (event) {
        this.form.patchValue({
          name: event.name,
          description: event.description,
          category: event.category,
          venueId: event.venue.id,
          startsAt: toLocalDateTimeInput(event.startsAt),
          endsAt: toLocalDateTimeInput(event.endsAt),
        });
        this.sections.clearValidators();
      }
    });
  }

  protected addSection(): void {
    this.sections.push(
      this.fb.nonNullable.group({
        name: [nextSectionName(this.sections.length), Validators.required],
        rowCount: [5, [Validators.required, Validators.min(1)]],
        seatsPerRow: [10, [Validators.required, Validators.min(1)]],
        priceTier: ['STANDARD' as PriceTier, Validators.required],
        price: [25, [Validators.required, Validators.min(0)]],
      }),
    );
  }

  protected removeSection(index: number): void {
    this.sections.removeAt(index);
  }

  protected createVenue(): void {
    if (this.venueForm.invalid) {
      return;
    }
    this.busy.set(true);
    this.catalog.createVenue(this.venueForm.getRawValue()).subscribe({
      next: (venue) => {
        this.venues.update((vs) => [...vs, venue]);
        this.form.patchValue({ venueId: venue.id });
        this.showVenueForm.set(false);
        this.venueForm.reset();
        this.busy.set(false);
        this.snackBar.open(`Venue "${venue.name}" created.`, 'OK', { duration: 3000 });
      },
      error: () => {
        this.busy.set(false);
        this.snackBar.open('Could not create the venue.', 'OK', { duration: 4000 });
      },
    });
  }

  protected submit(): void {
    if (this.form.invalid) {
      return;
    }
    const value = this.form.getRawValue();
    const base = {
      name: value.name,
      description: value.description,
      category: value.category,
      venueId: value.venueId,
      startsAt: new Date(value.startsAt).toISOString(),
      endsAt: new Date(value.endsAt).toISOString(),
    };
    this.busy.set(true);

    const existing = this.event();
    const request$ = existing
      ? this.catalog.updateEvent(existing.id, base)
      : this.catalog.createEvent({
          ...base,
          sections: (value.sections as SectionFormValue[]).map(
            (s): SectionSpec => ({
              name: s.name,
              rowCount: Number(s.rowCount),
              seatsPerRow: Number(s.seatsPerRow),
              priceTier: s.priceTier,
              price: Number(s.price),
            }),
          ),
        });

    request$.subscribe({
      next: (event) => {
        this.busy.set(false);
        this.saved.emit(event);
      },
      error: () => {
        this.busy.set(false);
        this.snackBar.open('Saving the event failed. Check the fields.', 'OK', {
          duration: 4000,
        });
      },
    });
  }

  private loadVenues(): void {
    this.catalog.getVenues().subscribe({
      next: (venues) => this.venues.set(venues),
      error: () => undefined,
    });
  }
}

function toLocalDateTimeInput(iso: string): string {
  const date = new Date(iso);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

function nextSectionName(index: number): string {
  return String.fromCharCode('A'.charCodeAt(0) + (index % 26));
}

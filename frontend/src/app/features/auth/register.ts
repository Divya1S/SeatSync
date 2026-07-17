import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatRadioModule } from '@angular/material/radio';
import { switchMap } from 'rxjs';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-register',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatRadioModule,
  ],
  template: `
    <div class="auth-wrap">
      <mat-card class="auth-card">
        <mat-card-header>
          <mat-card-title>Create your SeatSync account</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="submit()">
            <mat-form-field appearance="outline" class="full">
              <mat-label>Full name</mat-label>
              <input matInput formControlName="fullName" autocomplete="name" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="full">
              <mat-label>Email</mat-label>
              <input matInput type="email" formControlName="email" autocomplete="email" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="full">
              <mat-label>Password</mat-label>
              <input
                matInput
                type="password"
                formControlName="password"
                autocomplete="new-password"
              />
              <mat-hint>At least 8 characters</mat-hint>
            </mat-form-field>

            <p class="role-label">I want to…</p>
            <mat-radio-group formControlName="role" class="role-group">
              <mat-radio-button value="ATTENDEE">Attend events</mat-radio-button>
              <mat-radio-button value="ORGANIZER">Organize events</mat-radio-button>
            </mat-radio-group>

            @if (error(); as message) {
              <p class="error">{{ message }}</p>
            }
            <button
              mat-flat-button
              type="submit"
              class="full"
              [disabled]="form.invalid || busy()"
            >
              Register
            </button>
          </form>
        </mat-card-content>
        <mat-card-actions>
          <span class="hint">Already registered?</span>
          <a mat-button routerLink="/login">Log in</a>
        </mat-card-actions>
      </mat-card>
    </div>
  `,
  styles: `
    .auth-wrap { display: flex; justify-content: center; padding-top: 48px; }
    .auth-card { width: 440px; max-width: 100%; }
    .full { width: 100%; }
    .role-label { margin: 4px 0; opacity: 0.8; }
    .role-group { display: flex; gap: 16px; margin-bottom: 16px; }
    .error { color: var(--mat-sys-error); }
    .hint { opacity: 0.7; margin-left: 8px; }
    mat-card-actions { align-items: center; }
  `,
})
export class Register {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly form = this.fb.nonNullable.group({
    fullName: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
    role: ['ATTENDEE' as 'ATTENDEE' | 'ORGANIZER', Validators.required],
  });

  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected submit(): void {
    if (this.form.invalid) {
      return;
    }
    const { fullName, email, password, role } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.auth
      .register({ email, password, fullName, role })
      .pipe(switchMap(() => this.auth.login(email, password)))
      .subscribe({
        next: () => {
          this.busy.set(false);
          const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/';
          this.router.navigateByUrl(returnUrl);
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(
            err?.status === 409
              ? 'That email is already registered.'
              : 'Registration failed. Please check your details and try again.',
          );
        },
      });
  }
}

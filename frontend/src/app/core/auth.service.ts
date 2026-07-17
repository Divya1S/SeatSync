import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, finalize, share, tap } from 'rxjs';
import { AuthResponse, RegisterRequest, Role, UserProfile } from './models';

const ACCESS_TOKEN_KEY = 'seatsync.accessToken';
const REFRESH_TOKEN_KEY = 'seatsync.refreshToken';
const USER_KEY = 'seatsync.user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly user = signal<UserProfile | null>(readStoredUser());
  /** In-memory copy of the tokens (localStorage is the persistent store). */
  private accessTokenValue: string | null = localStorage.getItem(ACCESS_TOKEN_KEY);
  private refreshTokenValue: string | null = localStorage.getItem(REFRESH_TOKEN_KEY);

  private refreshInFlight$: Observable<AuthResponse> | null = null;

  readonly currentUser = this.user.asReadonly();
  readonly isLoggedIn = computed(() => this.user() !== null);
  readonly isOrganizer = computed(() => this.hasRoleIn(this.user(), 'ORGANIZER'));

  get accessToken(): string | null {
    return this.accessTokenValue;
  }

  get refreshToken(): string | null {
    return this.refreshTokenValue;
  }

  hasRole(role: Role): boolean {
    return this.hasRoleIn(this.user(), role);
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>('/api/auth/login', { email, password })
      .pipe(tap((res) => this.storeSession(res)));
  }

  register(request: RegisterRequest): Observable<UserProfile> {
    return this.http.post<UserProfile>('/api/auth/register', request);
  }

  /**
   * Exchanges the refresh token for a new token pair. Concurrent callers share
   * a single in-flight request (the backend rotates + revokes on use).
   */
  refresh(): Observable<AuthResponse> {
    if (this.refreshInFlight$) {
      return this.refreshInFlight$;
    }
    const refreshToken = this.refreshTokenValue;
    this.refreshInFlight$ = this.http
      .post<AuthResponse>('/api/auth/refresh', { refreshToken })
      .pipe(
        tap((res) => this.storeSession(res)),
        finalize(() => (this.refreshInFlight$ = null)),
        share(),
      );
    return this.refreshInFlight$;
  }

  logout(redirect = true): void {
    this.accessTokenValue = null;
    this.refreshTokenValue = null;
    this.user.set(null);
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    if (redirect) {
      this.router.navigate(['/login']);
    }
  }

  private storeSession(res: AuthResponse): void {
    this.accessTokenValue = res.accessToken;
    this.refreshTokenValue = res.refreshToken;
    this.user.set(res.user);
    localStorage.setItem(ACCESS_TOKEN_KEY, res.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, res.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(res.user));
  }

  private hasRoleIn(user: UserProfile | null, role: Role): boolean {
    return !!user && user.roles.includes(role);
  }
}

function readStoredUser(): UserProfile | null {
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? (JSON.parse(raw) as UserProfile) : null;
  } catch {
    return null;
  }
}

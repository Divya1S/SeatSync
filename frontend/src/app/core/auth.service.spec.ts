import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors, HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';
import { AuthResponse } from './models';

function authResponse(overrides: Partial<AuthResponse> = {}): AuthResponse {
  return {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    tokenType: 'Bearer',
    expiresInSeconds: 900,
    user: {
      id: '00000000-0000-0000-0000-000000000003',
      email: 'attendee@seatsync.local',
      fullName: 'Demo Attendee',
      roles: ['ATTENDEE'],
    },
    ...overrides,
  };
}

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('login stores tokens and exposes the user via the currentUser signal', () => {
    service.login('attendee@seatsync.local', 'attendee1!').subscribe();

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      email: 'attendee@seatsync.local',
      password: 'attendee1!',
    });
    req.flush(authResponse());

    expect(service.accessToken).toBe('access-1');
    expect(service.refreshToken).toBe('refresh-1');
    expect(service.currentUser()?.email).toBe('attendee@seatsync.local');
    expect(service.isLoggedIn()).toBeTrue();
    expect(localStorage.getItem('seatsync.accessToken')).toBe('access-1');
    expect(localStorage.getItem('seatsync.refreshToken')).toBe('refresh-1');
  });

  it('refresh sends the stored refresh token and rotates the pair', () => {
    service.login('a@b.c', 'pw').subscribe();
    httpMock.expectOne('/api/auth/login').flush(authResponse());

    service.refresh().subscribe();
    const req = httpMock.expectOne('/api/auth/refresh');
    expect(req.request.body).toEqual({ refreshToken: 'refresh-1' });
    req.flush(authResponse({ accessToken: 'access-2', refreshToken: 'refresh-2' }));

    expect(service.accessToken).toBe('access-2');
    expect(service.refreshToken).toBe('refresh-2');
    expect(localStorage.getItem('seatsync.refreshToken')).toBe('refresh-2');
  });

  it('concurrent refresh calls share a single HTTP request', () => {
    service.login('a@b.c', 'pw').subscribe();
    httpMock.expectOne('/api/auth/login').flush(authResponse());

    let first: string | undefined;
    let second: string | undefined;
    service.refresh().subscribe((res) => (first = res.accessToken));
    service.refresh().subscribe((res) => (second = res.accessToken));

    const req = httpMock.expectOne('/api/auth/refresh'); // exactly one request
    req.flush(authResponse({ accessToken: 'access-2', refreshToken: 'refresh-2' }));

    expect(first).toBe('access-2');
    expect(second).toBe('access-2');
  });

  it('logout clears tokens, user signal and localStorage', () => {
    service.login('a@b.c', 'pw').subscribe();
    httpMock.expectOne('/api/auth/login').flush(authResponse());

    service.logout(false);

    expect(service.accessToken).toBeNull();
    expect(service.refreshToken).toBeNull();
    expect(service.currentUser()).toBeNull();
    expect(service.isLoggedIn()).toBeFalse();
    expect(localStorage.getItem('seatsync.accessToken')).toBeNull();
    expect(localStorage.getItem('seatsync.user')).toBeNull();
  });

  it('restores the stored user on construction', () => {
    localStorage.setItem(
      'seatsync.user',
      JSON.stringify({ id: 'u1', email: 'x@y.z', fullName: 'X', roles: ['ORGANIZER'] }),
    );
    localStorage.setItem('seatsync.accessToken', 'stored-access');

    // Fresh injector so the service re-reads localStorage.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const fresh = TestBed.inject(AuthService);

    expect(fresh.currentUser()?.email).toBe('x@y.z');
    expect(fresh.hasRole('ORGANIZER')).toBeTrue();
    expect(fresh.accessToken).toBe('stored-access');
    httpMock = TestBed.inject(HttpTestingController);
  });
});

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  function loginViaMock(): void {
    auth.login('a@b.c', 'pw').subscribe();
    httpMock.expectOne('/api/auth/login').flush(authResponse());
  }

  it('attaches the Bearer token to API requests', () => {
    loginViaMock();
    http.get('/api/bookings/mine').subscribe();
    const req = httpMock.expectOne('/api/bookings/mine');
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-1');
    req.flush([]);
  });

  it('does not attach the token to auth endpoints', () => {
    loginViaMock();
    auth.refresh().subscribe();
    const req = httpMock.expectOne('/api/auth/refresh');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush(authResponse({ accessToken: 'access-2', refreshToken: 'refresh-2' }));
  });

  it('on 401 it refreshes once and retries the request with the new token', () => {
    loginViaMock();

    let result: unknown;
    http.get('/api/holds/mine').subscribe((res) => (result = res));

    httpMock
      .expectOne('/api/holds/mine')
      .flush({ detail: 'expired' }, { status: 401, statusText: 'Unauthorized' });

    httpMock
      .expectOne('/api/auth/refresh')
      .flush(authResponse({ accessToken: 'access-2', refreshToken: 'refresh-2' }));

    const retried = httpMock.expectOne('/api/holds/mine');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer access-2');
    retried.flush([{ holdId: 'h1' }]);

    expect(result).toEqual([{ holdId: 'h1' }]);
  });

  it('logs out and stops retrying when the refresh itself fails', () => {
    loginViaMock();

    let errored = false;
    http.get('/api/holds/mine').subscribe({ error: () => (errored = true) });

    httpMock
      .expectOne('/api/holds/mine')
      .flush(null, { status: 401, statusText: 'Unauthorized' });
    httpMock
      .expectOne('/api/auth/refresh')
      .flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(errored).toBeTrue();
    expect(auth.isLoggedIn()).toBeFalse();
    expect(localStorage.getItem('seatsync.accessToken')).toBeNull();
  });
});

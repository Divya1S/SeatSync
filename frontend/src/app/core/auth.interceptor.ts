import {
  HttpErrorResponse,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const PUBLIC_AUTH_ENDPOINTS = ['/api/auth/login', '/api/auth/register', '/api/auth/refresh'];

function isPublicAuthEndpoint(url: string): boolean {
  return PUBLIC_AUTH_ENDPOINTS.some((path) => url.includes(path));
}

/**
 * Attaches `Authorization: Bearer <accessToken>` to every /api call except
 * login/register/refresh. On a 401 it attempts a single token refresh and
 * retries the request; if that fails the session is cleared and the user is
 * redirected to /login with a returnUrl.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (isPublicAuthEndpoint(req.url)) {
    return next(req);
  }

  return next(withBearer(req, auth.accessToken)).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && auth.refreshToken) {
        return retryWithRefresh(req, next, auth, router);
      }
      return throwError(() => error);
    }),
  );
};

function retryWithRefresh(
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
  auth: AuthService,
  router: Router,
): Observable<never> | ReturnType<HttpHandlerFn> {
  return auth.refresh().pipe(
    switchMap((res) => next(withBearer(req, res.accessToken))),
    catchError((refreshError: unknown) => {
      auth.logout(false);
      router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
      return throwError(() => refreshError);
    }),
  );
}

function withBearer(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  if (!token) {
    return req;
  }
  return req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
}

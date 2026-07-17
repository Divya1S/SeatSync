import { Injectable, signal } from '@angular/core';

/**
 * Single app-wide 1s ticker. Components derive countdowns with
 * `computed(() => formatCountdown(expiresAt, ticker.now()))` so one interval
 * drives every countdown in the app.
 */
@Injectable({ providedIn: 'root' })
export class CountdownService {
  private readonly nowMs = signal(Date.now());

  readonly now = this.nowMs.asReadonly();

  constructor() {
    setInterval(() => this.nowMs.set(Date.now()), 1000);
  }
}

/** Milliseconds remaining until the ISO instant, clamped at 0. */
export function remainingMs(expiresAt: string, nowMs: number): number {
  const remaining = Date.parse(expiresAt) - nowMs;
  return Number.isNaN(remaining) ? 0 : Math.max(0, remaining);
}

/** Formats the time remaining until `expiresAt` as `mm:ss` (clamped at 00:00). */
export function formatCountdown(expiresAt: string, nowMs: number): string {
  const totalSeconds = Math.ceil(remainingMs(expiresAt, nowMs) / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${pad(minutes)}:${pad(seconds)}`;
}

export function isExpired(expiresAt: string, nowMs: number): boolean {
  return remainingMs(expiresAt, nowMs) <= 0;
}

function pad(n: number): string {
  return n.toString().padStart(2, '0');
}

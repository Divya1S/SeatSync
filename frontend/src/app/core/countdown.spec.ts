import { formatCountdown, isExpired, remainingMs } from './countdown';

describe('countdown utils', () => {
  const now = Date.parse('2026-07-16T18:00:00Z');

  it('formats a 5 minute hold as mm:ss', () => {
    expect(formatCountdown('2026-07-16T18:05:00Z', now)).toBe('05:00');
    expect(formatCountdown('2026-07-16T18:04:59.000Z', now)).toBe('04:59');
    expect(formatCountdown('2026-07-16T18:00:07Z', now)).toBe('00:07');
  });

  it('rounds partial seconds up so the timer never shows more time than remains', () => {
    expect(formatCountdown('2026-07-16T18:00:06.500Z', now)).toBe('00:07');
  });

  it('clamps at 00:00 once expired', () => {
    expect(formatCountdown('2026-07-16T17:59:59Z', now)).toBe('00:00');
    expect(remainingMs('2026-07-16T17:00:00Z', now)).toBe(0);
  });

  it('handles countdowns longer than an hour without truncation', () => {
    expect(formatCountdown('2026-07-16T19:30:00Z', now)).toBe('90:00');
  });

  it('treats malformed timestamps as expired', () => {
    expect(formatCountdown('not-a-date', now)).toBe('00:00');
    expect(isExpired('not-a-date', now)).toBeTrue();
  });

  it('isExpired flips exactly at the deadline', () => {
    expect(isExpired('2026-07-16T18:00:01Z', now)).toBeFalse();
    expect(isExpired('2026-07-16T18:00:00Z', now)).toBeTrue();
  });
});

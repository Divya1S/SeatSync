import { SeatMap, SeatState } from './models';
import { applySeatUpdate, buildSectionViews, seatUiState } from './seat-state';

const seatMap: SeatMap = {
  eventId: 'e1',
  sections: [
    {
      name: 'A',
      priceTier: 'STANDARD',
      price: 49,
      rows: [
        {
          label: '1',
          seats: [
            { seatId: 'A-1-1', number: 1, price: 49 },
            { seatId: 'A-1-2', number: 2, price: 49 },
            { seatId: 'A-1-3', number: 3, price: 49 },
            { seatId: 'A-1-4', number: 4, price: 49 },
          ],
        },
      ],
    },
  ],
};

function state(seatId: string, status: SeatState['status'], mine = false): SeatState {
  return { seatId, status, mine };
}

describe('seatUiState', () => {
  it('maps CONVENTIONS §9 seat states to UI states', () => {
    expect(seatUiState(undefined)).toBe('available');
    expect(seatUiState({ status: 'AVAILABLE', mine: false })).toBe('available');
    expect(seatUiState({ status: 'HELD', mine: true })).toBe('held-mine');
    expect(seatUiState({ status: 'HELD', mine: false })).toBe('held-other');
    expect(seatUiState({ status: 'BOOKED', mine: false })).toBe('booked');
  });
});

describe('buildSectionViews', () => {
  it('overlays live seat states onto the static seat map', () => {
    const states = new Map<string, SeatState>([
      ['A-1-2', { ...state('A-1-2', 'HELD', true), holdExpiresAt: '2026-07-16T18:05:00Z' }],
      ['A-1-3', state('A-1-3', 'HELD', false)],
      ['A-1-4', state('A-1-4', 'BOOKED')],
    ]);

    const sections = buildSectionViews(seatMap, states);
    const seats = sections[0].rows[0].seats;

    expect(sections.length).toBe(1);
    expect(seats.map((s) => s.ui)).toEqual(['available', 'held-mine', 'held-other', 'booked']);
    expect(seats[1].holdExpiresAt).toBe('2026-07-16T18:05:00Z');
    expect(seats[0].price).toBe(49);
  });

  it('treats seats without a live state as AVAILABLE', () => {
    const sections = buildSectionViews(seatMap, new Map());
    for (const seat of sections[0].rows[0].seats) {
      expect(seat.ui).toBe('available');
      expect(seat.status).toBe('AVAILABLE');
    }
  });
});

describe('applySeatUpdate', () => {
  it('marks a broadcast HELD seat as mine only when it matches my hold', () => {
    const states = new Map<string, SeatState>();
    const mine = applySeatUpdate(states, { seatId: 'A-1-1', status: 'HELD' }, new Set(['A-1-1']));
    const other = applySeatUpdate(states, { seatId: 'A-1-2', status: 'HELD' }, new Set(['A-1-1']));

    expect(mine.get('A-1-1')).toEqual(
      jasmine.objectContaining({ status: 'HELD', mine: true }),
    );
    expect(other.get('A-1-2')).toEqual(
      jasmine.objectContaining({ status: 'HELD', mine: false }),
    );
  });

  it('preserves my hold expiry across a HELD re-broadcast and is immutable', () => {
    const states = new Map<string, SeatState>([
      ['A-1-1', { ...state('A-1-1', 'HELD', true), holdExpiresAt: '2026-07-16T18:05:00Z' }],
    ]);
    const next = applySeatUpdate(states, { seatId: 'A-1-1', status: 'HELD' }, new Set(['A-1-1']));

    expect(next).not.toBe(states as Map<string, SeatState>);
    expect(next.get('A-1-1')?.holdExpiresAt).toBe('2026-07-16T18:05:00Z');
  });

  it('a BOOKED or AVAILABLE update clears ownership', () => {
    const states = new Map<string, SeatState>([['A-1-1', state('A-1-1', 'HELD', true)]]);
    const booked = applySeatUpdate(states, { seatId: 'A-1-1', status: 'BOOKED' }, new Set());
    const freed = applySeatUpdate(states, { seatId: 'A-1-1', status: 'AVAILABLE' }, new Set());

    expect(booked.get('A-1-1')).toEqual(
      jasmine.objectContaining({ status: 'BOOKED', mine: false }),
    );
    expect(freed.get('A-1-1')).toEqual(
      jasmine.objectContaining({ status: 'AVAILABLE', mine: false }),
    );
  });
});

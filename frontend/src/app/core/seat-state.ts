import { SeatMap, SeatMapSeat, SeatState, SeatStatus } from './models';

/**
 * Visual state of a single seat button (§9 seat map colors):
 *  - available:  primary/outlined, clickable
 *  - held-mine:  accent, shows countdown
 *  - held-other: greyed, disabled
 *  - booked:     dark, disabled
 */
export type SeatUiState = 'available' | 'held-mine' | 'held-other' | 'booked';

export interface SeatView extends SeatMapSeat {
  status: SeatStatus;
  mine: boolean;
  holdExpiresAt?: string;
  ui: SeatUiState;
}

export interface SectionView {
  name: string;
  priceTier: string;
  price: number;
  rows: { label: string; seats: SeatView[] }[];
}

/**
 * Derives the UI state for a seat from its live booking state.
 * A seat with no live state entry (e.g. inventory not seeded yet) is AVAILABLE.
 */
export function seatUiState(state: Pick<SeatState, 'status' | 'mine'> | undefined): SeatUiState {
  if (!state || state.status === 'AVAILABLE') {
    return 'available';
  }
  if (state.status === 'BOOKED') {
    return 'booked';
  }
  return state.mine ? 'held-mine' : 'held-other';
}

/**
 * Merges the static seat map (catalog) with live seat states (booking service +
 * STOMP updates) into renderable sections/rows/seats.
 */
export function buildSectionViews(
  seatMap: SeatMap,
  states: ReadonlyMap<string, SeatState>,
): SectionView[] {
  return seatMap.sections.map((section) => ({
    name: section.name,
    priceTier: section.priceTier,
    price: section.price,
    rows: section.rows.map((row) => ({
      label: row.label,
      seats: row.seats.map((seat) => {
        const state = states.get(seat.seatId);
        return {
          ...seat,
          status: state?.status ?? 'AVAILABLE',
          mine: state?.mine ?? false,
          holdExpiresAt: state?.holdExpiresAt,
          ui: seatUiState(state),
        };
      }),
    })),
  }));
}

/** Applies a live STOMP seat update to the state map, returning a new map. */
export function applySeatUpdate(
  states: ReadonlyMap<string, SeatState>,
  update: { seatId: string; status: SeatStatus },
  myHoldSeatIds: ReadonlySet<string>,
): Map<string, SeatState> {
  const next = new Map(states);
  const previous = next.get(update.seatId);
  const mine = update.status === 'HELD' && myHoldSeatIds.has(update.seatId);
  next.set(update.seatId, {
    seatId: update.seatId,
    status: update.status,
    mine,
    holdExpiresAt: mine ? previous?.holdExpiresAt : undefined,
  });
  return next;
}

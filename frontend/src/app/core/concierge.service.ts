import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { ConciergeResponse, ConciergeSource, Confidence } from './models';

export interface ChatMessage {
  from: 'user' | 'assistant';
  text: string;
  sources?: ConciergeSource[];
  confidence?: Confidence;
  escalatedToHuman?: boolean;
  at: number;
}

@Injectable({ providedIn: 'root' })
export class ConciergeService {
  private readonly http = inject(HttpClient);

  private readonly conversationIdValue = signal<string | null>(null);
  private readonly messages = signal<ChatMessage[]>([]);
  private readonly busy = signal(false);

  /** Full chat transcript, kept across panel open/close and route changes. */
  readonly transcript = this.messages.asReadonly();
  readonly pending = this.busy.asReadonly();
  readonly conversationId = this.conversationIdValue.asReadonly();

  send(message: string): Observable<ConciergeResponse> {
    this.messages.update((m) => [...m, { from: 'user', text: message, at: Date.now() }]);
    this.busy.set(true);
    return this.http
      .post<ConciergeResponse>('/api/concierge/chat', {
        message,
        conversationId: this.conversationIdValue(),
      })
      .pipe(
        tap({
          next: (res) => {
            this.conversationIdValue.set(res.conversationId);
            this.messages.update((m) => [
              ...m,
              {
                from: 'assistant',
                text: res.answer,
                sources: res.sources,
                confidence: res.confidence,
                escalatedToHuman: res.escalatedToHuman,
                at: Date.now(),
              },
            ]);
            this.busy.set(false);
          },
          error: () => {
            this.messages.update((m) => [
              ...m,
              {
                from: 'assistant',
                text: 'Something went wrong reaching the concierge. Please try again or email support@seatsync.local.',
                escalatedToHuman: true,
                at: Date.now(),
              },
            ]);
            this.busy.set(false);
          },
        }),
      );
  }

  reset(): void {
    this.conversationIdValue.set(null);
    this.messages.set([]);
  }
}

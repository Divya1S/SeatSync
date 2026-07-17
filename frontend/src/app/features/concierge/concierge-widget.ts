import { Component, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatExpansionModule } from '@angular/material/expansion';
import { ConciergeService } from '../../core/concierge.service';

@Component({
  selector: 'app-concierge-widget',
  imports: [
    DecimalPipe,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatExpansionModule,
  ],
  template: `
    @if (open()) {
      <mat-card class="chat-panel">
        <mat-card-header class="chat-header">
          <mat-card-title>
            <mat-icon>support_agent</mat-icon>
            SeatSync Concierge
          </mat-card-title>
          <span class="spacer"></span>
          <button mat-icon-button (click)="concierge.reset()" title="New conversation">
            <mat-icon>refresh</mat-icon>
          </button>
          <button mat-icon-button (click)="open.set(false)" title="Close">
            <mat-icon>close</mat-icon>
          </button>
        </mat-card-header>

        <mat-card-content class="chat-messages" #scroller>
          @if (concierge.transcript().length === 0) {
            <p class="empty-hint">
              Ask me about events, refunds, holds, accessibility or anything SeatSync.
            </p>
          }
          @for (msg of concierge.transcript(); track msg.at) {
            <div class="bubble-row" [class.from-user]="msg.from === 'user'">
              <div class="bubble" [class.user]="msg.from === 'user'">
                <p class="text">{{ msg.text }}</p>

                @if (msg.from === 'assistant' && msg.confidence) {
                  <span class="badge" [class]="'badge ' + msg.confidence.toLowerCase()">
                    {{ msg.confidence }} confidence
                  </span>
                }

                @if (msg.escalatedToHuman) {
                  <div class="escalated">
                    <mat-icon inline>support_agent</mat-icon>
                    This was escalated to a human — email
                    <strong>support&#64;seatsync.local</strong> for help.
                  </div>
                }

                @if (msg.sources && msg.sources.length > 0) {
                  <mat-expansion-panel class="sources">
                    <mat-expansion-panel-header>
                      <mat-panel-title>Sources ({{ msg.sources.length }})</mat-panel-title>
                    </mat-expansion-panel-header>
                    <mat-chip-set>
                      @for (source of msg.sources; track source.title) {
                        <mat-chip [title]="source.snippet">
                          {{ source.title }} · {{ source.score | number: '1.2-2' }}
                        </mat-chip>
                      }
                    </mat-chip-set>
                  </mat-expansion-panel>
                }
              </div>
            </div>
          }
          @if (concierge.pending()) {
            <div class="bubble-row">
              <div class="bubble"><mat-spinner diameter="20" /></div>
            </div>
          }
        </mat-card-content>

        <mat-card-actions class="chat-input">
          <mat-form-field appearance="outline" class="grow" subscriptSizing="dynamic">
            <input
              matInput
              placeholder="Ask the concierge…"
              [(ngModel)]="draft"
              (keyup.enter)="send()"
              [disabled]="concierge.pending()"
            />
          </mat-form-field>
          <button
            mat-icon-button
            (click)="send()"
            [disabled]="!draft.trim() || concierge.pending()"
          >
            <mat-icon>send</mat-icon>
          </button>
        </mat-card-actions>
      </mat-card>
    }

    <button mat-fab class="chat-fab" (click)="toggle()" aria-label="AI concierge">
      <mat-icon>{{ open() ? 'close' : 'chat' }}</mat-icon>
    </button>
  `,
  styles: `
    :host { position: fixed; bottom: 24px; right: 24px; z-index: 1000; }
    .chat-fab { position: absolute; bottom: 0; right: 0; }
    .chat-panel {
      position: absolute; bottom: 72px; right: 0;
      width: 380px; max-width: calc(100vw - 48px);
      height: 520px; max-height: calc(100vh - 160px);
      display: flex; flex-direction: column;
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
    }
    .chat-header { align-items: center; }
    .chat-header mat-card-title { display: flex; align-items: center; gap: 8px; font-size: 1rem; }
    .spacer { flex: 1; }
    .chat-messages { flex: 1; overflow-y: auto; padding: 12px; }
    .empty-hint { opacity: 0.6; text-align: center; margin-top: 40px; }
    .bubble-row { display: flex; margin: 8px 0; }
    .bubble-row.from-user { justify-content: flex-end; }
    .bubble {
      max-width: 85%; border-radius: 12px; padding: 8px 12px;
      background: var(--mat-sys-surface-variant);
    }
    .bubble.user { background: var(--mat-sys-primary-container); }
    .text { margin: 0; white-space: pre-wrap; }
    .badge {
      display: inline-block; font-size: 0.7rem; font-weight: 700;
      border-radius: 10px; padding: 2px 8px; margin-top: 6px; color: #fff;
    }
    .badge.high { background: #2e7d32; }
    .badge.medium { background: #f9a825; color: #333; }
    .badge.low { background: #c62828; }
    .escalated {
      margin-top: 8px; padding: 8px; border-radius: 8px;
      background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container);
      font-size: 0.85rem; display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
    }
    .sources { margin-top: 8px; box-shadow: none; background: transparent; }
    .chat-input { display: flex; gap: 4px; padding: 8px; }
    .chat-input .grow { flex: 1; }
  `,
})
export class ConciergeWidget {
  protected readonly concierge = inject(ConciergeService);
  protected readonly open = signal(false);
  protected draft = '';

  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  constructor() {
    // Keep the newest message in view.
    effect(() => {
      this.concierge.transcript();
      this.concierge.pending();
      const el = this.scroller()?.nativeElement;
      if (el) {
        setTimeout(() => el.scrollTo({ top: el.scrollHeight }));
      }
    });
  }

  protected toggle(): void {
    this.open.update((o) => !o);
  }

  protected send(): void {
    const message = this.draft.trim();
    if (!message || this.concierge.pending()) {
      return;
    }
    this.draft = '';
    this.concierge.send(message).subscribe({ error: () => undefined });
  }
}

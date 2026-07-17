import { Injectable, OnDestroy } from '@angular/core';
import { Client, IMessage, ReconnectionTimeMode, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Observable, Subject } from 'rxjs';
import { SeatEventMessage } from './models';

interface TopicEntry {
  subject: Subject<SeatEventMessage>;
  refCount: number;
  stompSub?: StompSubscription;
}

/**
 * STOMP over SockJS to /ws (proxied to the API gateway). One shared client,
 * one topic per event: /topic/events/{eventId}/seats. Reconnects with
 * exponential backoff and resubscribes all active topics on reconnect.
 */
@Injectable({ providedIn: 'root' })
export class SeatSocketService implements OnDestroy {
  private client: Client | null = null;
  private readonly topics = new Map<string, TopicEntry>();

  /** Live seat updates for one event. Unsubscribe to release the topic. */
  seatUpdates(eventId: string): Observable<SeatEventMessage> {
    return new Observable<SeatEventMessage>((observer) => {
      const entry = this.acquireTopic(eventId);
      const sub = entry.subject.subscribe(observer);
      return () => {
        sub.unsubscribe();
        this.releaseTopic(eventId);
      };
    });
  }

  ngOnDestroy(): void {
    this.client?.deactivate();
    this.client = null;
  }

  private acquireTopic(eventId: string): TopicEntry {
    let entry = this.topics.get(eventId);
    if (!entry) {
      entry = { subject: new Subject<SeatEventMessage>(), refCount: 0 };
      this.topics.set(eventId, entry);
    }
    entry.refCount++;
    const client = this.ensureClient();
    if (client.connected && !entry.stompSub) {
      this.subscribeTopic(client, eventId, entry);
    }
    return entry;
  }

  private releaseTopic(eventId: string): void {
    const entry = this.topics.get(eventId);
    if (!entry) {
      return;
    }
    entry.refCount--;
    if (entry.refCount <= 0) {
      try {
        entry.stompSub?.unsubscribe();
      } catch {
        // connection may already be gone
      }
      entry.subject.complete();
      this.topics.delete(eventId);
    }
  }

  private ensureClient(): Client {
    if (this.client) {
      return this.client;
    }
    const client = new Client({
      webSocketFactory: () => new SockJS('/ws') as WebSocket,
      reconnectDelay: 1000,
      maxReconnectDelay: 30000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        // (Re)subscribe every active topic after connect/reconnect.
        for (const [eventId, entry] of this.topics) {
          this.subscribeTopic(client, eventId, entry);
        }
      },
      onWebSocketClose: () => {
        for (const entry of this.topics.values()) {
          entry.stompSub = undefined;
        }
      },
    });
    client.activate();
    this.client = client;
    return client;
  }

  private subscribeTopic(client: Client, eventId: string, entry: TopicEntry): void {
    entry.stompSub = client.subscribe(`/topic/events/${eventId}/seats`, (frame: IMessage) => {
      try {
        entry.subject.next(JSON.parse(frame.body) as SeatEventMessage);
      } catch {
        // ignore malformed frames
      }
    });
  }
}

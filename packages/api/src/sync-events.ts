import type { DatabaseHandle } from "@mue/db";
import { currentSequence } from "@mue/db";
import { Hono } from "hono";
import type { AuthedEnv } from "./auth-routes";

/**
 * `GET /api/v1/sync/events` — the live channel of PRD section 9.4.
 *
 * ## Why this exists
 *
 * Section 9.4 lists when the phone attempts a synchronisation and ends with
 * "périodiquement par le mécanisme Android approprié, sans promesse d'heure
 * exacte". Between two of those attempts the phone is simply wrong, and the
 * owner has no way to know it other than pressing `Sync now` — which is the one
 * thing a local-first application must never make him do to answer "am I up to
 * date?". This endpoint removes the question: while somebody is looking at the
 * screen, the server tells the phone the moment its journal moves.
 *
 * Section 6 excludes "synchronisation en temps réel lorsque le téléphone se
 * trouve **hors du réseau autorisé**". It excludes real time when the server
 * cannot be reached — not real time when it can. Nothing here changes what an
 * unreachable server costs: the stream fails to open, the client waits, and the
 * periodic worker remains the only promise.
 *
 * Section 8.3's "le transport SSE historique n'est pas implémenté" is a sentence
 * inside "### 8.3 Transports MCP", about the deprecated MCP HTTP+SSE transport
 * that `/mcp` deliberately does not offer in favour of Streamable HTTP; section
 * 23 repeats it as "Quel transport MCP est visé ?". It says nothing about the
 * Android sync transport, which section 20.4 defines separately.
 *
 * ## Why Server-Sent Events and not a WebSocket
 *
 * - It is one more route on the HTTPS listener section 22.5 already restricts to
 *   the private network. No second port, no second certificate, no second
 *   authentication path: the `Authorization: Bearer` of PRD 9.2 is read by the
 *   same `requireSession` guard as every other `/api/v1` route, because a
 *   WebSocket upgrade cannot carry that header from a browser and would have
 *   needed a ticket endpoint of its own.
 * - It survives a reverse proxy. Section 20.5 puts the platform behind one in a
 *   deployment, and an ordinary chunked `text/event-stream` response is the
 *   thing every proxy already forwards; an `Upgrade: websocket` is the thing
 *   each one has to be configured for.
 * - It is unidirectional, which is exactly the traffic: the phone has nothing to
 *   say on this channel. Everything it sends goes through `POST /sync/push`,
 *   which is idempotent, batched and already proven. A bidirectional socket
 *   would invite a second write path.
 * - There is no third party in it. No FCM, no push provider, no account with
 *   anyone: section 6 rules out any vendor network integration, and a home
 *   server that is never publicly exposed could not receive a webhook anyway.
 *
 * ## What travels, and what deliberately does not
 *
 * Nothing. An event carries `data: {}`.
 *
 * The temptation is to send the journal sequence so the phone can tell how far
 * behind it is. Section 12.3 makes the sequence the server's, and the client
 * cursor opaque; a client that learns to read a position off this channel is a
 * client that will one day construct one. So the sequence is compared here,
 * inside the server, and the wire carries the fact that it moved and not the
 * value it moved to. The phone treats every event as one word — *pull* — and
 * `POST /sync/pull` with its own stored cursor remains the only thing that ever
 * decides what it is missing.
 *
 * That also makes the stream carry no personal data at all, which is what lets
 * it stay open for minutes at a time under section 16 without becoming a second
 * place weights can leak.
 *
 * ## Why it polls the counter rather than listening to the writers
 *
 * An in-process emitter fed by `submitMutations` would be instant and would miss
 * the case this was built for. The owner writes from a Web interface, an agent
 * writes through `/mcp`, and a row can be inserted straight into PostgreSQL
 * through Adminer; only some of those are this process. `sync_counter` is the
 * one row every accepted change must move — `allocateSequence` takes a lock on
 * it inside the writing transaction — so reading it catches every writer, in any
 * process, with no trigger, no migration and no `LISTEN` connection.
 *
 * The read is a primary-key lookup of one `bigint`. {@link POLL_INTERVAL_MS} of
 * them per open stream, and a stream is only open while the phone is in the
 * foreground.
 */

/**
 * How long a change waits, at worst, before the phone hears about it.
 *
 * Two seconds is the whole added latency budget: a change is announced on
 * average one second after it commits, which is below what anybody perceives as
 * a delay when they put the laptop down and pick the phone up. Shorter buys
 * nothing a human can see and multiplies the queries; longer starts to feel like
 * the polling it replaces.
 */
export const POLL_INTERVAL_MS = 2_000;

/**
 * A comment line, often enough that nothing between the two ends decides the
 * connection is dead.
 *
 * It is not decoration. An idle TCP connection through a NAT or a proxy is
 * reaped silently, and a phone holding a socket nobody will ever write to
 * believes it is live while missing every change. Twenty seconds is comfortably
 * under the shortest idle timeout in common use, and a comment is two bytes of
 * payload that no SSE parser turns into an event.
 */
export const HEARTBEAT_INTERVAL_MS = 20_000;

/**
 * The longest one stream lives before the server closes it and the client opens
 * another.
 *
 * A stream is authorised once, when it opens. Section 9.3 lets the owner revoke
 * an Android session, and section 15.3 wants that to take effect — a connection
 * that lives for the whole afternoon would keep answering a session deleted an
 * hour ago. Closing it periodically puts the credential back through
 * `requireSession`, and costs the client one reconnection it is already built to
 * handle. It is deliberately not short: a reconnection is cheap, but it is not
 * free.
 */
export const MAX_STREAM_MS = 30 * 60 * 1_000;

export interface SyncEventRouteOptions {
  readonly database: DatabaseHandle;
  /** Overridable so the tests do not spend real seconds proving a tick. */
  readonly pollIntervalMs?: number;
  readonly heartbeatIntervalMs?: number;
  readonly maxStreamMs?: number;
}

function frame(event: string): string {
  return `event: ${event}\ndata: {}\n\n`;
}

export function createSyncEventRoutes(options: SyncEventRouteOptions): Hono<AuthedEnv> {
  const routes = new Hono<AuthedEnv>();
  const pollIntervalMs = options.pollIntervalMs ?? POLL_INTERVAL_MS;
  const heartbeatIntervalMs = options.heartbeatIntervalMs ?? HEARTBEAT_INTERVAL_MS;
  const maxStreamMs = options.maxStreamMs ?? MAX_STREAM_MS;

  routes.get("/events", (c) => {
    const userId = c.get("userId");
    const signal = c.req.raw.signal;

    const body = new ReadableStream<Uint8Array>({
      /**
       * The loop is started here and deliberately **not** awaited.
       *
       * `start` is the stream's constructor, not its body. A `start` that returns a
       * promise settling only when the connection ends leaves the stream
       * perpetually starting: `Bun.serve` flushes whatever was enqueued before the
       * first suspension point and then ends the response. That failure looks
       * exactly like a working channel from a short test — the greeting arrives,
       * and the heartbeat twenty seconds later never does, because by then there is
       * no connection left to send it on. Detaching the loop is what makes this a
       * stream rather than a slow single response.
       */
      start(controller) {
        void pump(controller, userId, signal, {
          pollIntervalMs,
          heartbeatIntervalMs,
          maxStreamMs,
          database: options.database,
        });
      },
    });

    return new Response(body, {
      status: 200,
      headers: {
        "content-type": "text/event-stream; charset=utf-8",
        // `no-transform` is the half that matters: a proxy that gzips this
        // response buffers it, and a buffered event stream is a stream that
        // delivers everything at once when it closes.
        "cache-control": "no-cache, no-store, no-transform",
        // nginx's own opt-out, since `no-transform` alone does not stop its
        // proxy buffer.
        "x-accel-buffering": "no",
        connection: "keep-alive",
      },
    });
  });

  return routes;
}

interface PumpOptions {
  readonly database: DatabaseHandle;
  readonly pollIntervalMs: number;
  readonly heartbeatIntervalMs: number;
  readonly maxStreamMs: number;
}

/**
 * One connection, from the greeting to the close.
 *
 * It owns the whole lifetime of the stream and never rejects: a database that went
 * away, a peer that walked off and a controller that is already closed are all the
 * same outcome here — the stream ends, and the client's backoff decides what
 * happens next. There is no error frame in this protocol on purpose (FR-SYNC-008).
 */
async function pump(
  controller: ReadableStreamDefaultController<Uint8Array>,
  userId: string,
  signal: AbortSignal,
  options: PumpOptions,
): Promise<void> {
  const encoder = new TextEncoder();
  let closed = false;

  const close = (): void => {
    if (closed) return;
    closed = true;
    try {
      controller.close();
    } catch {
      // Already closed by the peer going away. Nothing to report: a phone that
      // walks out of the house is the normal case, not a fault.
    }
  };

  const send = (chunk: string): boolean => {
    if (closed) return false;
    try {
      controller.enqueue(encoder.encode(chunk));
      return true;
    } catch {
      close();
      return false;
    }
  };

  signal.addEventListener("abort", close, { once: true });

  /**
   * The sequence this stream has already announced. Read *before* the first frame
   * goes out, so a change that commits while the client is connecting is announced
   * by the next tick rather than swallowed by a baseline taken too late.
   */
  let announced: bigint;
  try {
    announced = await currentSequence(options.database, userId);
  } catch {
    close();
    return;
  }

  // Opening the stream is itself a hint. A client reconnecting after a dropped
  // connection cannot know whether the journal moved while it was away, and it
  // must not be told — that would be a position. So every successful connection
  // ends in one pull, and the pull's own cursor decides whether there was anything
  // to fetch.
  if (!send(frame("hello"))) return;

  const startedAt = Date.now();
  let lastBeat = startedAt;

  while (!closed && !signal.aborted) {
    await sleep(options.pollIntervalMs, signal);
    if (closed || signal.aborted) break;

    if (Date.now() - startedAt >= options.maxStreamMs) break;

    let sequence: bigint;
    try {
      sequence = await currentSequence(options.database, userId);
    } catch {
      // Same judgement as above, one level down: the stream ends without saying
      // why, and the client's backoff owns what happens next.
      break;
    }

    if (sequence > announced) {
      announced = sequence;
      if (!send(frame("change"))) break;
      lastBeat = Date.now();
      continue;
    }

    if (Date.now() - lastBeat >= options.heartbeatIntervalMs) {
      if (!send(": heartbeat\n\n")) break;
      lastBeat = Date.now();
    }
  }

  close();
}

/**
 * A cancellable wait.
 *
 * `setTimeout` alone would hold the loop for a full interval after the phone has
 * already gone, which on a server with a long poll interval means a database
 * query issued for a connection that no longer exists.
 */
function sleep(ms: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) return Promise.resolve();
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    const onAbort = (): void => {
      clearTimeout(timer);
      resolve();
    };
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

import { Link, createFileRoute } from "@tanstack/react-router";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactElement } from "react";

/**
 * `/settings/agents` -- the page section 15.3 assigns to the Web administration:
 * *"L'administration Web prévue par PRD_WEB.md listera les sessions, appareils et
 * agents associés. Elle affiche leur dernière utilisation et leurs portées. Une
 * identité peut être révoquée immédiatement."*
 *
 * Until it existed, opening a pairing window meant two `curl` calls: one to sign in
 * and keep the cookie, one to `POST /api/v1/agents/pairing`. That cookie lived in
 * `curl`'s jar, so the browser the MCP client then opened had no session and asked
 * the owner to authenticate a second time before showing the consent page. Nothing
 * was broken; the only place to perform an owner action was a terminal, and a
 * terminal cannot hand a session to a browser. Signing in *here*, once, is the fix.
 *
 * The pairing step itself does not go away and cannot: a client must hold a
 * `client_id` before it can be sent to an authorization page, and
 * `packages/api/src/mcp/registration.ts` explains at length why registration is not
 * simply left open on a network anyone on the WiFi can reach (section 16: *"Le
 * caractère privé du réseau ne remplace ni l'authentification ni le chiffrement."*).
 * The step becomes a button. It stays short, in memory, and owner-authenticated.
 *
 * `scripts/admin.ts` keeps every capability this page has. Section 15.3 required it
 * before the Web product shipped; it is now the troubleshooting path -- the one that
 * still works when this page cannot be reached.
 *
 *
 * ## The countdown and the restart
 *
 * The window lives only in the server process. A restart closes it, and no HTTP call
 * announces that. A timer started from one `until` instant and left to run would keep
 * counting down over a server that has forgotten the window ever opened, and the
 * owner would learn the truth from a client that failed to register.
 *
 * So nothing here is trusted to a local clock alone:
 *
 *  - the countdown is derived from the absolute instant the *server* named, never
 *    from a duration decremented locally, so it cannot drift;
 *  - the server is re-asked every {@link PAIRING_POLL_INTERVAL_MS}, and its answer
 *    replaces whatever the page believed -- a restart is visible within one poll;
 *  - a poll that does not come back does not leave the previous answer running: the
 *    page says it cannot reach the server and stops the countdown;
 *  - an answer older than {@link PAIRING_TRUST_MS} is not shown as a countdown at
 *    all, which is what a throttled background tab produces.
 *
 * `pairingDisplay` is the whole rule and is a pure function, so the four cases are
 * proven in `settings.agents.test.tsx` rather than argued about here.
 */

/** How often the server is re-asked what the window is really doing. */
export const PAIRING_POLL_INTERVAL_MS = 5_000;

/**
 * How stale the last answer may be before the page stops showing a countdown.
 *
 * Three polls: a missed one is ordinary -- a slow query, a tab briefly throttled --
 * and three in a row means nothing is refreshing this page's belief any more.
 */
export const PAIRING_TRUST_MS = 3 * PAIRING_POLL_INTERVAL_MS;

/**
 * Mirrors `DEFAULT_PAIRING_MINUTES` and `MAX_PAIRING_MINUTES` in
 * `packages/api/src/mcp/registration.ts`, which is the authority: it clamps whatever
 * arrives. Importing them would pull `@mue/api`, and with it Hono, Better Auth and
 * Drizzle, into the browser bundle -- section 15.1 keeps that graph server-side.
 * These two numbers only spare the owner a round trip to be told 500 means 60.
 */
export const DEFAULT_PAIRING_MINUTES = 10;
export const MAX_PAIRING_MINUTES = 60;

/** The body of `GET/POST/DELETE /api/v1/agents/pairing`. */
export interface PairingState {
  readonly open: boolean;
  /** ISO instant the window closes by itself, or null when it is closed. */
  readonly until: string | null;
}

/** What the page has actually been told, and when it was told it. */
export type PairingKnowledge =
  | { readonly kind: "asking" }
  | { readonly kind: "unreachable"; readonly askedAtMs: number }
  | { readonly kind: "closed"; readonly answeredAtMs: number }
  | { readonly kind: "open"; readonly untilMs: number; readonly answeredAtMs: number };

/** What that entitles the page to put on screen. */
export type PairingDisplay =
  | { readonly kind: "asking" }
  | { readonly kind: "unreachable" }
  | { readonly kind: "stale" }
  | { readonly kind: "closed" }
  | { readonly kind: "open"; readonly remainingMs: number };

export function knowledgeFromAnswer(state: PairingState, atMs: number): PairingKnowledge {
  if (!state.open || state.until === null) return { kind: "closed", answeredAtMs: atMs };
  const untilMs = Date.parse(state.until);
  // An instant we cannot read is not an open window. Better to under-claim.
  if (Number.isNaN(untilMs)) return { kind: "closed", answeredAtMs: atMs };
  return { kind: "open", untilMs, answeredAtMs: atMs };
}

export function knowledgeFromFailure(atMs: number): PairingKnowledge {
  return { kind: "unreachable", askedAtMs: atMs };
}

export function pairingDisplay(knowledge: PairingKnowledge, nowMs: number): PairingDisplay {
  if (knowledge.kind === "asking") return { kind: "asking" };
  if (knowledge.kind === "unreachable") return { kind: "unreachable" };
  if (knowledge.kind === "closed") return { kind: "closed" };
  if (nowMs - knowledge.answeredAtMs > PAIRING_TRUST_MS) return { kind: "stale" };
  const remainingMs = knowledge.untilMs - nowMs;
  // The deadline is the server's own. Reaching it is not a guess.
  if (remainingMs <= 0) return { kind: "closed" };
  return { kind: "open", remainingMs };
}

/** `m:ss`, rounded up so a window with 200 ms left does not read as closed. */
export function formatRemaining(ms: number): string {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, "0")}`;
}

export function clampMinutes(value: number): number {
  if (!Number.isFinite(value)) return 1;
  return Math.min(Math.max(Math.floor(value), 1), MAX_PAIRING_MINUTES);
}

/** One row of `GET /api/v1/agents`. */
export interface AgentRow {
  readonly clientId: string;
  readonly name: string | null;
  readonly scopes: readonly string[];
  readonly revoked: boolean;
  readonly discovered: boolean;
  readonly registeredAt: string | null;
  readonly lastUsedAt: string | null;
}

/**
 * Live agents first, newest registration first inside each group.
 *
 * The server orders by `clientId`, which is a random string: it puts the agent
 * registered thirty seconds ago in an arbitrary place in the list, and the one thing
 * the owner is looking for right after opening a window is the agent that just
 * appeared. Revoked rows stay -- section 14.7 keeps the client row so the audit is
 * readable -- but they belong underneath.
 */
export function sortAgents(agents: readonly AgentRow[]): AgentRow[] {
  const registered = (agent: AgentRow): number =>
    agent.registeredAt === null ? Number.NEGATIVE_INFINITY : Date.parse(agent.registeredAt);
  return [...agents].sort((left, right) => {
    if (left.revoked !== right.revoked) return left.revoked ? 1 : -1;
    return registered(right) - registered(left);
  });
}

/** A date the owner reads, or the word for not having one. */
function formatInstant(iso: string | null): string {
  if (iso === null) return "never";
  const value = new Date(iso);
  if (Number.isNaN(value.getTime())) return "unknown";
  return value.toLocaleString();
}

/**
 * A 401 on any of these calls means the cookie is gone, not that the request was
 * malformed. It is thrown apart from every other failure so the page can send the
 * owner to sign in once rather than show an error it cannot help with.
 */
class SessionGone extends Error {
  constructor() {
    super("This browser is not signed in.");
    this.name = "SessionGone";
  }
}

async function call(path: string, init: RequestInit = {}): Promise<unknown> {
  const response = await fetch(path, {
    ...init,
    // The cookie is `HttpOnly`, so this is the only way the page can present it, and
    // it is why nothing here has to hold a token.
    credentials: "same-origin",
    // Constant, and on every verb including the ones with no body. A JSON content
    // type is not a CORS-simple request, so a page on another origin cannot reach
    // these routes without a preflight this server never answers -- which is what
    // stands between a link in a message and a revoked agent.
    headers: { "content-type": "application/json" },
  });
  if (response.status === 401) throw new SessionGone();

  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    const error = (body as { error?: { message?: string } } | null)?.error;
    throw new Error(error?.message ?? "The server refused that.");
  }
  return body;
}

function readPairingState(body: unknown): PairingState {
  const record = (body ?? {}) as { open?: unknown; until?: unknown };
  return {
    open: record.open === true,
    until: typeof record.until === "string" ? record.until : null,
  };
}

function readAgents(body: unknown): AgentRow[] {
  const record = (body ?? {}) as { agents?: unknown };
  return Array.isArray(record.agents) ? (record.agents as AgentRow[]) : [];
}

export const Route = createFileRoute("/settings/agents")({ component: AgentsPage });

export function AgentsPage(): ReactElement {
  const [knowledge, setKnowledge] = useState<PairingKnowledge>({ kind: "asking" });
  /**
   * Starts at zero rather than at `Date.now()` so the server-rendered HTML and the
   * first browser render are the same bytes. Nothing time-dependent is on screen
   * until the first answer arrives, and the tick below sets it immediately.
   */
  const [nowMs, setNowMs] = useState(0);
  const [agents, setAgents] = useState<readonly AgentRow[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [minutes, setMinutes] = useState(DEFAULT_PAIRING_MINUTES);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);

  /** Read by the poll, which is created once and must not close over a stale value. */
  const windowOpen = useRef(false);

  const signInAgain = useCallback(() => {
    // A full navigation, not a router one: the session is a cookie the server sets,
    // and the page that follows must be rendered with it.
    window.location.assign(`/sign-in?next=${encodeURIComponent("/settings/agents")}`);
  }, []);

  const refreshAgents = useCallback(async (): Promise<void> => {
    setAgents(sortAgents(readAgents(await call("/api/v1/agents"))));
  }, []);

  const refreshPairing = useCallback(async (): Promise<void> => {
    try {
      const state = readPairingState(await call("/api/v1/agents/pairing"));
      windowOpen.current = state.open;
      setKnowledge(knowledgeFromAnswer(state, Date.now()));
    } catch (error) {
      if (error instanceof SessionGone) throw error;
      // Anything else -- the server is restarting, the network dropped, the reverse
      // proxy is between two workers -- means this page no longer knows. It does not
      // mean the previous answer is still true.
      windowOpen.current = false;
      setKnowledge(knowledgeFromFailure(Date.now()));
    }
  }, []);

  // The clock. Separate from the poll so the countdown is smooth without asking the
  // server every second.
  useEffect(() => {
    setNowMs(Date.now());
    const tick = setInterval(() => {
      setNowMs(Date.now());
    }, 1000);
    return () => {
      clearInterval(tick);
    };
  }, []);

  // The poll. The pairing window every time; the agent list only while a window is
  // open, which is the only time an agent can appear without the owner doing
  // something on this page.
  useEffect(() => {
    let stopped = false;

    const poll = (): void => {
      if (stopped) return;
      void (async () => {
        try {
          await refreshPairing();
          if (windowOpen.current) await refreshAgents();
        } catch (error) {
          if (error instanceof SessionGone) {
            stopped = true;
            signInAgain();
          }
        }
      })();
    };

    void (async () => {
      try {
        await refreshAgents();
      } catch (error) {
        if (error instanceof SessionGone) {
          stopped = true;
          signInAgain();
          return;
        }
        setProblem(error instanceof Error ? error.message : "The agent list did not load.");
      }
      poll();
    })();

    const interval = setInterval(poll, PAIRING_POLL_INTERVAL_MS);
    // A background tab is throttled, so its last answer goes stale and the countdown
    // stops. Coming back asks again immediately instead of waiting for the interval.
    const onVisible = (): void => {
      if (document.visibilityState === "visible") poll();
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      stopped = true;
      clearInterval(interval);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [refreshAgents, refreshPairing, signInAgain]);

  const act = (work: () => Promise<void>): void => {
    setBusy(true);
    setProblem(null);
    work()
      .catch((error: unknown) => {
        if (error instanceof SessionGone) {
          signInAgain();
          return;
        }
        setProblem(error instanceof Error ? error.message : "That did not work.");
      })
      .finally(() => {
        setBusy(false);
      });
  };

  const openWindow = (): void => {
    act(async () => {
      const state = readPairingState(
        await call("/api/v1/agents/pairing", {
          method: "POST",
          body: JSON.stringify({ minutes: clampMinutes(minutes) }),
        }),
      );
      windowOpen.current = state.open;
      setKnowledge(knowledgeFromAnswer(state, Date.now()));
    });
  };

  const closeWindow = (): void => {
    act(async () => {
      const state = readPairingState(await call("/api/v1/agents/pairing", { method: "DELETE" }));
      windowOpen.current = state.open;
      setKnowledge(knowledgeFromAnswer(state, Date.now()));
      await refreshAgents();
    });
  };

  const revoke = (clientId: string): void => {
    act(async () => {
      await call(`/api/v1/agents/${encodeURIComponent(clientId)}`, { method: "DELETE" });
      setConfirming(null);
      await refreshAgents();
    });
  };

  const display = pairingDisplay(knowledge, nowMs);

  return (
    <main>
      <p className="quiet">
        <Link to="/">Mue Platform</Link> / Settings
      </p>
      <h1>Agents</h1>
      <p>
        An agent is a program you have authorised to read or write your Mue data over MCP. Each one
        has its own identity and its own permissions, and you can take either away here.
      </p>

      <PairingWindowPanel
        display={display}
        minutes={minutes}
        busy={busy}
        onMinutes={setMinutes}
        onOpen={openWindow}
        onClose={closeWindow}
        onRetry={() => {
          void refreshPairing();
        }}
      />

      <h2>Registered agents</h2>
      {agents === null ? (
        <p>Loading…</p>
      ) : agents.length === 0 ? (
        <p>
          No agent has registered yet. Open the pairing window above, then point your MCP client at{" "}
          <code>/mcp</code> on this server.
        </p>
      ) : (
        <ul className="cards">
          {agents.map((agent) => (
            <AgentCard
              key={agent.clientId}
              agent={agent}
              busy={busy}
              confirming={confirming === agent.clientId}
              onAskConfirm={() => {
                setProblem(null);
                setConfirming(agent.clientId);
              }}
              onCancel={() => {
                setConfirming(null);
              }}
              onConfirm={() => {
                revoke(agent.clientId);
              }}
            />
          ))}
        </ul>
      )}

      {problem !== null && <p role="alert">{problem}</p>}

      <p className="quiet">
        Everything on this page is also available from the repository root with{" "}
        <code>bun run scripts/admin.ts agents list</code>, which is what to reach for when this page
        itself cannot be loaded.
      </p>
    </main>
  );
}

interface PairingWindowPanelProps {
  readonly display: PairingDisplay;
  readonly minutes: number;
  readonly busy: boolean;
  readonly onMinutes: (minutes: number) => void;
  readonly onOpen: () => void;
  readonly onClose: () => void;
  readonly onRetry: () => void;
}

function PairingWindowPanel(props: PairingWindowPanelProps): ReactElement {
  const { display, minutes, busy, onMinutes, onOpen, onClose, onRetry } = props;

  return (
    <section className="card" aria-labelledby="pairing-heading">
      <h2 id="pairing-heading">Pairing window</h2>

      {/*
        The countdown is deliberately not a live region. It changes every second, and
        a screen reader announcing it every second would make the page unusable; the
        sentence underneath announces the state changes that actually matter.
      */}
      {display.kind === "open" ? (
        <>
          <p className="countdown" aria-hidden="true">
            {formatRemaining(display.remainingMs)}
          </p>
          <p aria-live="polite">
            The window is open for another {formatRemaining(display.remainingMs)}. A new client can
            register itself now.
          </p>
          <button type="button" onClick={onClose} disabled={busy}>
            Close it now
          </button>
        </>
      ) : display.kind === "asking" ? (
        <p aria-live="polite">Asking the server…</p>
      ) : display.kind === "unreachable" ? (
        <>
          <p role="alert">
            The server did not answer, so this page cannot say whether the window is open. It is
            held in the server&apos;s memory, so a restart closes it.
          </p>
          <button type="button" onClick={onRetry} disabled={busy}>
            Ask again
          </button>
        </>
      ) : display.kind === "stale" ? (
        <>
          <p aria-live="polite">
            This page has not heard from the server recently enough to say what the window is doing.
          </p>
          <button type="button" onClick={onRetry} disabled={busy}>
            Ask again
          </button>
        </>
      ) : (
        <>
          <p aria-live="polite">
            Registration is closed. Opening the window lets one new client register itself with this
            server; it does not grant it anything, and you still decide the permissions on the
            authorization page that follows.
          </p>
          <label className="inline">
            <span>Minutes</span>
            <input
              type="number"
              min={1}
              max={MAX_PAIRING_MINUTES}
              step={1}
              value={minutes}
              disabled={busy}
              onChange={(event) => {
                onMinutes(Number(event.currentTarget.value));
              }}
            />
          </label>
          <button type="button" className="primary" onClick={onOpen} disabled={busy}>
            Pair a new agent
          </button>
          <p className="quiet">
            The window closes by itself, and restarting the server closes it too — it is never
            written down.
          </p>
        </>
      )}
    </section>
  );
}

interface AgentCardProps {
  readonly agent: AgentRow;
  readonly busy: boolean;
  readonly confirming: boolean;
  readonly onAskConfirm: () => void;
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
}

function AgentCard(props: AgentCardProps): ReactElement {
  const { agent, busy, confirming, onAskConfirm, onCancel, onConfirm } = props;

  return (
    <li className="card">
      <h3>
        {agent.name ?? agent.clientId}{" "}
        <span className={agent.revoked ? "badge revoked" : "badge"}>
          {agent.revoked ? "Revoked" : "Active"}
        </span>
      </h3>
      <dl>
        <dt>Client id</dt>
        <dd>
          <code>{agent.clientId}</code>
        </dd>
        <dt>Registered</dt>
        <dd>
          {formatInstant(agent.registeredAt)}
          {agent.discovered && " (through a metadata document)"}
        </dd>
        <dt>Last seen</dt>
        <dd>{formatInstant(agent.lastUsedAt)}</dd>
        <dt>Permissions</dt>
        <dd>
          {agent.scopes.length === 0 ? (
            "none"
          ) : (
            /*
              The scope tokens themselves, not the sentences the consent page writes
              for them. This is an administration listing: `weight:read` is what the
              token carries, what `scripts/admin.ts agents list` prints and what an
              audit row names. A third copy of the scope vocabulary -- consent.tsx
              already notes that its own is a second -- would be the thing that drifts.
            */
            <span className="chips">
              {agent.scopes.map((scope) => (
                <code key={scope}>{scope}</code>
              ))}
            </span>
          )}
        </dd>
      </dl>

      {agent.revoked ? (
        <p className="quiet">
          Its tokens were revoked and its consent removed. The identity is kept so the audit trail
          still names it.
        </p>
      ) : confirming ? (
        <div role="group" aria-label={`Revoke ${agent.name ?? agent.clientId}`}>
          <p role="alert">
            Revoke {agent.name ?? agent.clientId}? Its tokens stop working immediately and it will
            have to be authorised again from scratch. This cannot be undone.
          </p>
          <button type="button" className="danger" onClick={onConfirm} disabled={busy} autoFocus>
            Yes, revoke it
          </button>{" "}
          <button type="button" onClick={onCancel} disabled={busy}>
            Keep it
          </button>
        </div>
      ) : (
        <button type="button" onClick={onAskConfirm} disabled={busy}>
          Revoke
        </button>
      )}
    </li>
  );
}

export default AgentsPage;

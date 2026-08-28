import type { MueAuth } from "@mue/auth";
import type { MueError } from "@mue/contracts";
import { Hono } from "hono";
import { type AuthedEnv, requireSession } from "../auth-routes";

/**
 * RFC 7591 dynamic client registration, and the pairing window that keeps it
 * from being an open endpoint on a network anyone on the WiFi can reach.
 *
 *
 * ## Why the endpoint has to exist at all
 *
 * A shipping MCP client mints a fresh loopback redirect path for every session:
 *
 *     http://127.0.0.1:33418/callback/UW4qsoeKLHI2
 *
 * and the suffix is different on the next run. OAuth requires the `redirect_uri`
 * of an authorization request to match a registered one, and RFC 8252 section
 * 7.3 relaxes exactly one component of that match for loopback redirects -- the
 * port, because the client cannot reserve one. Not the path. Better Auth
 * implements precisely that, and nothing wider, in `findRegisteredRedirectUri`
 * (`@better-auth/oauth-provider`, dist/authorize-Crqw4_bR.mjs):
 *
 *     return registered.find((url) => {
 *       if (url === requested) return true;
 *       if (!req) return false;
 *       try {
 *         const reg = new URL(url);
 *         return isLoopbackIP(reg.hostname) && reg.hostname === req.hostname
 *           && reg.pathname === req.pathname && reg.protocol === req.protocol
 *           && reg.search === req.search;
 *       } catch { return false; }
 *     });
 *
 * `reg.pathname === req.pathname`. A client registered once by hand with
 * `/callback` is refused the moment it asks for `/callback/UW4qsoeKLHI2`, and
 * registering that one solves nothing because the next session invents another.
 * Widening the server to match a path prefix would be a real hole -- the path is
 * what distinguishes two programs sharing the loopback interface -- so the
 * server stays right and the client registers the URI it is about to use. That
 * is what this endpoint is for.
 *
 * The alternative Better Auth offers, a Client ID Metadata Document, cannot work
 * here: a CIMD `client_id` is an HTTPS URL the server fetches, and
 * `packages/auth/src/ssrf.ts` refuses every RFC 6890 special-purpose address by
 * design (PRD section 8.1). A home network has no other kind.
 *
 *
 * ## Why it is not simply left open
 *
 * The MCP SDK registers with a bare JSON POST carrying no credential, so RFC
 * 7591 section 3.1's initial access token and Better Auth's session-backed mode
 * both close the endpoint to every client it exists for. Enabling
 * `allowUnauthenticatedClientRegistration` is therefore the only setting that
 * works -- and on its own it would leave an unauthenticated write endpoint on
 * the LAN, which PRD section 16 refuses in as many words:
 *
 *     Le caractère privé du réseau ne remplace ni l'authentification ni le
 *     chiffrement.
 *
 * Being on the owner's WiFi authorises nothing. So Better Auth never sees a
 * registration request unless the owner has opened a **pairing window** first,
 * the way a device is put into pairing mode before it will be paired. Outside
 * one, this module answers 401 and the request stops here.
 *
 * The window is deliberately small and deliberately in memory:
 *
 *  - it lasts minutes, not for ever, and {@link MAX_PAIRING_MINUTES} caps what
 *    the owner can ask for, so a window left open by accident closes itself;
 *  - it lives in the process, so a restart closes it and no row can outlive the
 *    intent that created it;
 *  - it is opened over an authenticated route, so opening it is itself an act
 *    the owner has to be signed in to perform.
 *
 * It is not single-use. A client that registers, fails on the next step and
 * retries inside the same window is behaving correctly, and the window is
 * already the narrow thing.
 *
 * What registration grants, even inside the window, is nothing: a client row and
 * a `client_id`. Reaching any Mue data still requires the owner to sign in at
 * the login page and approve the scopes on the consent page, which is section
 * 15.2's "la configuration personnelle peut accorder toutes les portées à un
 * agent de confiance" -- the server offers, the owner decides. A client
 * registered by a stranger during a window the owner opened is a row the owner
 * never consents to, and `scripts/admin.ts agents list` shows it.
 */

/** Where Better Auth serves RFC 7591 registration, under its `/api/auth` base path. */
export const CLIENT_REGISTRATION_PATH = "/api/auth/oauth2/register";

/** Where the owner opens and closes the pairing window. */
export const PAIRING_PATH = "/api/v1/agents/pairing";

export const DEFAULT_PAIRING_MINUTES = 10;
export const MAX_PAIRING_MINUTES = 60;

export interface PairingState {
  readonly open: boolean;
  /** ISO instant the window closes by itself, or null when it is closed. */
  readonly until: string | null;
}

export interface PairingWindow {
  /** Opens, or extends, the window. Returns the state it leaves behind. */
  open(minutes: number): PairingState;
  close(): PairingState;
  state(): PairingState;
}

const CLOSED: PairingState = { open: false, until: null };

/**
 * The window itself. `now` is injected so a test can prove that it closes
 * without waiting ten minutes for it.
 */
export function createPairingWindow(now: () => number = Date.now): PairingWindow {
  let openUntil = 0;

  const state = (): PairingState =>
    openUntil > now() ? { open: true, until: new Date(openUntil).toISOString() } : CLOSED;

  return {
    open: (minutes) => {
      const clamped = Math.min(Math.max(Math.floor(minutes), 1), MAX_PAIRING_MINUTES);
      openUntil = now() + clamped * 60_000;
      return state();
    },
    close: () => {
      openUntil = 0;
      return CLOSED;
    },
    state,
  };
}

/**
 * The refusal, in the shape the caller is speaking.
 *
 * A client at this point is an OAuth client, not a Mue API client, so it reads
 * `error` and `error_description` rather than the Mue wire error. It is also
 * exactly the shape Better Auth itself uses to refuse this endpoint
 * (`registrationBearerError`), which means the gate and the provider behind it
 * answer the same way and a client never has to tell them apart.
 */
function registrationRefused(description: string): Response {
  return Response.json(
    { error: "invalid_token", error_description: description },
    {
      status: 401,
      headers: {
        "WWW-Authenticate": 'Bearer realm="mue", error="invalid_token"',
        "Cache-Control": "no-store",
        Pragma: "no-cache",
      },
    },
  );
}

/**
 * The exact loopback hosts Better Auth admits for a *native* client's `http`
 * redirect URI. It is a literal set and not "any loopback address": Better Auth
 * accepts `localhost`, `127.0.0.1` and `[::1]` and no other spelling, so
 * matching it exactly is what keeps {@link nativeApplicationType} from ever
 * turning one Better Auth error into a different one.
 */
const NATIVE_HTTP_LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

function isNativeHttpLoopbackRedirect(value: unknown): boolean {
  if (typeof value !== "string") return false;
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  return url.protocol === "http:" && NATIVE_HTTP_LOOPBACK_HOSTS.has(url.hostname);
}

/**
 * Infers `application_type: "native"` for a registration that declares only
 * `http` loopback redirect URIs and no application type of its own.
 *
 * Without this, dynamic registration rejects every MCP client on the planet.
 * Better Auth defaults a dynamic registration to `application_type: "web"`
 * (`applyOAuthClientRegistrationDefaults`), and a web client's redirect URI must
 * be HTTPS and must not be loopback:
 *
 *     if (applicationType === "web") {
 *       if (!isHttps || isRedirectLoopback)
 *         invalidRedirectUri(`web clients require https redirect URIs on
 *           non-loopback hosts: ${redirectUri}`);
 *
 * So a client sending `redirect_uris: ["http://127.0.0.1:33418/callback/…"]` --
 * which is every MCP client, because the MCP SDK's `registerClient` sends the
 * provider's `clientMetadata` verbatim and that metadata carries no
 * `application_type` -- gets 400 `invalid_redirect_uri` and stops.
 *
 * The inference is not a courtesy, it is the correct reading. RFC 7591 defines
 * no default for `application_type`; the "web" default comes from OpenID
 * Connect Dynamic Registration, and an `http` loopback redirect is the one
 * pattern RFC 8252 defines *for native apps* and forbids to web ones. A request
 * that declares an application type keeps it, wrong or right: this only fills a
 * blank, and only when every redirect URI agrees on the answer.
 */
export function nativeApplicationType(metadata: Record<string, unknown>): boolean {
  if (metadata["application_type"] !== undefined) return false;
  const redirects = metadata["redirect_uris"];
  if (!Array.isArray(redirects) || redirects.length === 0) return false;
  return redirects.every(isNativeHttpLoopbackRedirect);
}

/** Rebuilds the POST with the normalised body. */
function forwardedRegistration(request: Request, body: string): Request {
  const headers = new Headers(request.headers);
  // The body was re-serialised, so the inbound length is a lie. Bun computes the
  // right one when the header is absent; leaving the old one truncates the body
  // Better Auth parses, and the failure reads as invalid client metadata.
  headers.delete("content-length");
  return new Request(request.url, { method: "POST", headers, body });
}

export interface ClientRegistrationOptions {
  readonly auth: MueAuth;
  readonly pairing: PairingWindow;
}

/**
 * `POST /api/auth/oauth2/register` behind the pairing window, and the two routes
 * the owner uses to open and close it.
 *
 * Mounted *before* the catch-all `/api/auth/*` passthrough of `mountAuthRoutes`,
 * because that passthrough would otherwise hand the request straight to Better
 * Auth, which has been told to accept unauthenticated registrations.
 */
export function createClientRegistrationApp(options: ClientRegistrationOptions): Hono<AuthedEnv> {
  const { auth, pairing } = options;
  const app = new Hono<AuthedEnv>();

  app.post(CLIENT_REGISTRATION_PATH, async (c) => {
    // A signed-in owner is already authorised to create clients, and Better Auth
    // checks the privilege itself (`assertClientPrivileges`). Refusing that here
    // would make enabling the endpoint remove a path that used to work.
    let session: Awaited<ReturnType<MueAuth["api"]["getSession"]>> = null;
    try {
      session = await auth.api.getSession({ headers: c.req.raw.headers });
    } catch {
      session = null;
    }

    if (session === null && !pairing.state().open) {
      return registrationRefused(
        "Client registration is closed. The owner opens a pairing window with " +
          `POST ${PAIRING_PATH}.`,
      );
    }

    const body = await c.req.raw.text();
    let metadata: unknown;
    try {
      metadata = JSON.parse(body);
    } catch {
      // Not our error to invent. Better Auth answers a malformed body in the
      // registration error shape, and its message names the field.
      return auth.handler(forwardedRegistration(c.req.raw, body));
    }

    if (typeof metadata !== "object" || metadata === null || Array.isArray(metadata)) {
      return auth.handler(forwardedRegistration(c.req.raw, body));
    }

    const record = metadata as Record<string, unknown>;
    if (!nativeApplicationType(record)) {
      return auth.handler(forwardedRegistration(c.req.raw, body));
    }

    return auth.handler(
      forwardedRegistration(c.req.raw, JSON.stringify({ ...record, application_type: "native" })),
    );
  });

  // The owner's own routes. `createApiApp` guards `/api/v1/*` with the same
  // middleware, but this router is mounted ahead of it and answers first, so the
  // guard is repeated here rather than inherited -- an unguarded pairing switch
  // is the one thing this file exists to prevent.
  app.use(PAIRING_PATH, requireSession(auth));

  app.get(PAIRING_PATH, (c) => c.json(pairing.state()));

  app.post(PAIRING_PATH, async (c) => {
    let minutes = DEFAULT_PAIRING_MINUTES;
    try {
      const body = (await c.req.json()) as { minutes?: unknown };
      if (typeof body.minutes === "number" && Number.isFinite(body.minutes)) {
        minutes = body.minutes;
      }
    } catch {
      // An empty body is the ordinary case: `curl -X POST` with nothing to say.
    }
    return c.json(pairing.open(minutes));
  });

  app.delete(PAIRING_PATH, (c) => c.json(pairing.close()));

  app.on(["PUT", "PATCH"], PAIRING_PATH, (c) => {
    const error: MueError = {
      code: "http.method_not_allowed",
      message: "The pairing window is opened with POST and closed with DELETE.",
      retryable: false,
    };
    return c.json({ error }, 405);
  });

  return app;
}

import type { MueError } from "@mue/contracts";
import type { MueAuth } from "@mue/auth";
import type { Context, Hono, MiddlewareHandler } from "hono";

/**
 * Better Auth on Hono, and the guard every business route sits behind.
 *
 * PRD section 20.2 says Hono exposes Better Auth. Better Auth's documented Hono
 * integration is one line, and it is used as written: the plugin set decides
 * what `/api/auth/*` answers -- email and password, the Android `bearer` token,
 * the MCP OAuth endpoints -- so mounting individual endpoints by hand would
 * silently drop whichever plugin was added last.
 */

/** What the guard puts on the request for the routes behind it. */
export interface AuthVariables {
  readonly userId: string;
  readonly sessionId: string;
}

export type AuthedEnv = { Variables: AuthVariables };

const AUTH_PREFIX = "/api/auth/*";

export function mountAuthRoutes(app: Hono<AuthedEnv>, auth: MueAuth): void {
  // GET and POST only: Better Auth's handler routes on its own path, and
  // widening the verbs here would let it answer methods it never declared.
  app.on(["POST", "GET"], AUTH_PREFIX, (c) => auth.handler(c.req.raw));
}

function unauthorized(c: Context, message: string): Response {
  const error: MueError = { code: "auth.unauthenticated", message, retryable: false };
  // `WWW-Authenticate` is what tells an MCP client which resource to
  // authorise against; a bare 401 leaves it guessing (section 15.1).
  c.header("WWW-Authenticate", 'Bearer realm="mue"');
  return c.json({ error }, 401);
}

/**
 * Section 16: a route guard in a browser is never authorisation, so the session
 * is resolved server-side on every request from the credential the caller
 * actually presented -- the Android `Authorization: Bearer` or the Web cookie,
 * both of which Better Auth reads from the raw headers.
 *
 * A revoked session is a deleted row, so this returns 401 with no hint that the
 * token ever existed (section 15.3).
 */
export function requireSession(auth: MueAuth): MiddlewareHandler<AuthedEnv> {
  return async (c, next) => {
    let session: Awaited<ReturnType<MueAuth["api"]["getSession"]>> = null;
    try {
      session = await auth.api.getSession({ headers: c.req.raw.headers });
    } catch {
      // A malformed credential is not a server fault, and the reason belongs in
      // no response body: it is derived from what the caller sent.
      return unauthorized(c, "Sign in to synchronise.");
    }
    if (session === null) return unauthorized(c, "Sign in to synchronise.");

    c.set("userId", session.session.userId);
    c.set("sessionId", session.session.id);
    await next();
    return;
  };
}

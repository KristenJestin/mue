/**
 * Everything the identity layer reads from the environment.
 *
 * | Variable | Required | Meaning |
 * | --- | --- | --- |
 * | `BETTER_AUTH_SECRET` | yes | Master secret, at least 32 characters. No client ever receives it (section 15.1). |
 * | `BETTER_AUTH_URL` | yes | Public origin and OAuth issuer. HTTPS outside loopback (section 16). |
 * | `MUE_TRUSTED_ORIGINS` | no | Comma-separated origins allowed to call the auth endpoints. Defaults to the base URL alone. |
 * | `MUE_MCP_RESOURCE` | no | RFC 8707 protected-resource identifier. Defaults to `<base>/mcp`. |
 * | `MUE_LOGIN_PAGE` | no | Defaults to `/sign-in`. |
 * | `MUE_CONSENT_PAGE` | no | Defaults to `/consent`. |
 */
export type Env = Readonly<Record<string, string | undefined>>;

export interface AuthConfig {
  /** Better Auth master secret. No client ever receives it (section 15.1). */
  readonly secret: string;
  /** Public origin of the platform, the OAuth issuer. */
  readonly baseUrl: string;
  /**
   * Origins allowed to call the auth endpoints. Better Auth rejects a request
   * from anywhere else, which is section 16's "valide les hotes et origines".
   * Android sends no Origin, so the list is about the Web and MCP clients.
   */
  readonly trustedOrigins: readonly string[];
  /** RFC 8707 protected-resource identifier for the MCP endpoint. */
  readonly mcpResource: string;
  readonly loginPage: string;
  readonly consentPage: string;
  /** Whether cookies carry `Secure`. Only a loopback dev origin may say no. */
  readonly secureCookies: boolean;
}

function required(env: Env, name: string): string {
  const value = env[name];
  if (value === undefined || value.trim() === "") {
    throw new Error(`${name} is not set. The table on Env above says what it is for.`);
  }
  return value;
}

function isLoopbackOrigin(origin: string): boolean {
  try {
    const { hostname } = new URL(origin);
    return hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]";
  } catch {
    return false;
  }
}

export function readAuthConfig(env: Env = process.env): AuthConfig {
  const secret = required(env, "BETTER_AUTH_SECRET");
  if (secret.length < 32) {
    throw new Error("BETTER_AUTH_SECRET must be at least 32 characters.");
  }
  const baseUrl = required(env, "BETTER_AUTH_URL").replace(/\/+$/, "");
  const loopback = isLoopbackOrigin(baseUrl);
  if (!loopback && !baseUrl.startsWith("https://")) {
    // Section 16: the traffic is encrypted, and a private network is not a
    // substitute for it. Only a developer loopback origin is exempt.
    throw new Error(`BETTER_AUTH_URL must be https outside loopback, got ${baseUrl}`);
  }

  const origins = (env.MUE_TRUSTED_ORIGINS ?? "")
    .split(",")
    .map((origin) => origin.trim())
    .filter((origin) => origin.length > 0);

  return {
    secret,
    baseUrl,
    // The platform's own origin is always trusted; without at least one entry
    // Better Auth rejects every browser request and the failure looks like a
    // CORS bug rather than a missing setting.
    trustedOrigins: origins.length > 0 ? origins : [baseUrl],
    mcpResource: env.MUE_MCP_RESOURCE ?? `${baseUrl}/mcp`,
    loginPage: env.MUE_LOGIN_PAGE ?? "/sign-in",
    consentPage: env.MUE_CONSENT_PAGE ?? "/consent",
    secureCookies: !loopback,
  };
}

/**
 * The OAuth 2.1 issuer identifier: the origin, never `<origin>/api/auth`.
 *
 * This function is the whole fix for a real client that landed on a 404. Better
 * Auth's issuer defaults to its own base URL, which carries its base path, so it
 * was `https://host:3000/api/auth`. RFC 8414 then puts the authorization-server
 * metadata at `/.well-known/oauth-authorization-server/api/auth`, and OpenID
 * Connect Discovery at `/api/auth/.well-known/openid-configuration` -- both of
 * which the server answered, and neither of which the client asked for. It
 * looked at the origin, found `/.well-known/openid-configuration` 404, fell back
 * to the origin defaults RFC 8414 has no opinion about, and sent the human to
 * `https://host:3000/authorize`. 404. The metadata was correct and unread.
 *
 * Making the issuer the origin puts the two documents at the two paths every
 * client checks first, and `issuer` in them equals the URL they were fetched
 * from, which is what RFC 8414 section 3.3 requires a strict client to verify.
 * The endpoints do not move: an issuer is an identifier, not a directory, so the
 * metadata still names `<origin>/api/auth/oauth2/authorize` and that is still
 * where the endpoint is. Nothing is redirected and nothing is duplicated.
 *
 * The cost is that `iss` on every access token changes with it. Tokens minted
 * under the old issuer no longer verify, so an agent authorised before this
 * change authorises once more. `packages/api/src/mcp/route.ts` has to pass this
 * same value to `requireMcpAuth`, whose default is Better Auth's base URL and
 * would otherwise reject every token the server itself just signed.
 */
export function oauthIssuer(baseUrl: string): string {
  try {
    return new URL(baseUrl).origin;
  } catch {
    // `readAuthConfig` has already parsed the base URL, so this is unreachable
    // through it. A hand-built config gets its value back rather than a throw
    // from a function whose job is to name a string.
    return baseUrl;
  }
}

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

/**
 * Ce qui autorise un `BETTER_AUTH_URL` en clair hors de la boucle locale.
 *
 * La section 16 n'admet que HTTPS, et un réseau privé n'en est pas un substitut : le jeton de
 * session traverse le réseau à chaque synchronisation, et quiconque le lit n'obtient pas la
 * lecture d'un poids mais l'écriture sur des données de santé et l'accès aux outils MCP.
 *
 * L'échappatoire existe parce que le déploiement visé est un serveur domestique, sans nom de
 * domaine et sans autorité publique, dont le propriétaire a choisi le clair en connaissance de
 * cause. Elle demande une phrase entière plutôt qu'un `true` pour la même raison que
 * `MUE_ALLOW_DESTRUCTIVE_TESTS` : une valeur qu'on ne peut pas poser distraitement.
 *
 * Elle ne relâche **que** le contrôle de schéma. `secureCookies` ne la lit pas : il suit le
 * schéma réellement servi, faute de quoi un cookie marqué `Secure` ne partirait jamais sur une
 * origine en clair et l'authentification échouerait sans message.
 */
export const CLEARTEXT_VARIABLE = "MUE_ALLOW_CLEARTEXT";
export const CLEARTEXT_ACKNOWLEDGEMENT = "yes-in-clear-on-my-network";

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
  const cleartext = env[CLEARTEXT_VARIABLE] === CLEARTEXT_ACKNOWLEDGEMENT;
  if (!loopback && !cleartext && !baseUrl.startsWith("https://")) {
    // Section 16: the traffic is encrypted, and a private network is not a
    // substitute for it. A developer loopback origin is exempt, and so is a
    // deployment that has said in full what it is giving up -- see
    // CLEARTEXT_VARIABLE.
    throw new Error(
      `BETTER_AUTH_URL must be https outside loopback, got ${baseUrl}. ` +
        `Set ${CLEARTEXT_VARIABLE}=${CLEARTEXT_ACKNOWLEDGEMENT} to serve it in clear anyway.`,
    );
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
    // Le schéma réellement servi, et non `!loopback`.
    //
    // Les deux coïncidaient tant qu'une origine hors boucle locale était forcément en HTTPS.
    // Depuis que le clair est possible ailleurs, dériver de `loopback` marquerait le cookie
    // `Secure` sur une origine en `http://` — le navigateur ne l'enverrait alors jamais, et la
    // panne serait une session qui ne s'ouvre pas, sans erreur nulle part.
    secureCookies: baseUrl.startsWith("https://"),
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
 *
 * What the F-04 report later established, and what makes this load-bearing rather
 * than tidy: Codex never reads `authorization_servers` at all. rmcp, the Rust MCP
 * SDK it embeds, refuses an authorization-server candidate whose host is a private
 * address -- `is_allowed_authorization_server_metadata_url` consults
 * `Ipv4Addr::is_private()` and exempts only a loopback pair -- so on the owner's LAN
 * it skips ours and warns `rejecting authorization server metadata URL
 * https://<origin>/` at every initialisation. The trailing slash in that warning is
 * how `url::Url` prints an empty path, not anything this server writes; no document
 * of ours carries one. The warning predates this change, too: the entry named
 * `<origin>/api/auth` before, and was skipped just the same.
 *
 * What changed is what happens after the skip. rmcp falls back to an ungated probe
 * of the MCP endpoint's own origin, whose last candidate is
 * `<origin>/.well-known/oauth-authorization-server` -- a 404 until this function
 * moved the issuer, which is exactly why that client fell through to the legacy
 * `<origin>/authorize` and 404ed there. So that one document is the only route a
 * shipping client has to this authorization server, and it is reached only after
 * the documented route has been refused. `mcp.integration.test.ts` walks the whole
 * ladder rung by rung so it cannot quietly go away again.
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

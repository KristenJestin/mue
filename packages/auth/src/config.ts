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
    throw new Error(`${name} is not set. See .env.example.`);
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

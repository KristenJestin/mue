import { cimd } from "@better-auth/cimd";
import { mcp } from "@better-auth/mcp";
import { betterAuthSchema, createDatabase, type DatabaseHandle } from "@mue/db";
import { betterAuth } from "better-auth";
import { drizzleAdapter } from "better-auth/adapters/drizzle";
import { bearer, jwt } from "better-auth/plugins";
import { fetchClientMetadataResource } from "./cimd-transport";
import { readAuthConfig, type AuthConfig } from "./config";
import { OAUTH_SCOPES } from "./scopes";

/**
 * Better Auth is the identity authority for all three client shapes of
 * section 15.1: a Web cookie, an Android bearer token, and OAuth 2.1 + PKCE
 * for an agent. One human account, three ways to present it.
 */
function buildAuth(config: AuthConfig, database: DatabaseHandle) {
  return betterAuth({
    secret: config.secret,
    baseURL: config.baseUrl,
    trustedOrigins: [...config.trustedOrigins],

    // The Better Auth tables live in `mue_auth` because `betterAuthSchema` is
    // built from `pgSchema("mue_auth")`. The adapter looks each model up by
    // name in this object and lets Drizzle qualify it, so there is no second
    // connection and no `search_path` trick.
    database: drizzleAdapter(database.db, {
      provider: "pg",
      schema: betterAuthSchema,
      // Sign-up writes a user and an account; a failure must not leave half.
      transaction: true,
    }),

    emailAndPassword: {
      enabled: true,
      // A private single-user server has no mail transport (section 6). Email
      // verification would lock the only account out of its own instance.
      requireEmailVerification: false,
      minPasswordLength: 12,
    },

    session: {
      expiresIn: 60 * 60 * 24 * 30,
      updateAge: 60 * 60 * 24,
    },

    advanced: {
      // Sections 15.1 and 16: the Web session cookie is HttpOnly, Secure and
      // SameSite. HttpOnly is Better Auth's default and is restated here so a
      // future edit has to mean it.
      useSecureCookies: config.secureCookies,
      defaultCookieAttributes: {
        httpOnly: true,
        sameSite: "lax",
        secure: config.secureCookies,
      },
    },

    plugins: [
      /**
       * Android. Sign-in returns the session token in a `set-auth-token`
       * response header, and the app presents it as `Authorization: Bearer`.
       * The token is a session row, so one device is one row and deleting that
       * row revokes exactly that device -- sections 9.3 and 15.3.
       */
      bearer(),

      /** Signing keys for the access tokens an agent presents on /mcp. */
      jwt(),

      // @ts-expect-error better-auth 1.7.1 ships an endpoint metadata type that
      // `exactOptionalPropertyTypes` rejects: an OpenAPI parameter declares
      // `items?: undefined` where the target requires `{ type }`. The plugin is
      // correct at runtime and the repository keeps the strict flag, so the
      // mismatch is pinned to this one line. A Better Auth release that fixes
      // it turns this directive into an unused-suppression error, which is the
      // reminder to delete it.
      mcp({
        loginPage: config.loginPage,
        consentPage: config.consentPage,
        resource: config.mcpResource,
        scopes: [...OAUTH_SCOPES],
      }),

      /**
       * OAuth 2.1 + PKCE client discovery by Client ID Metadata Document.
       * `fetchClientMetadataResource` is a required option and is deliberately
       * ours: ./cimd-transport.ts explains why the shipped Node transport
       * cannot be used on Bun.
       */
      cimd({
        fetchClientMetadataResource,
        // MCP 2026-07-28 pins CIMD draft-00, which makes `client_name` and
        // `redirect_uris` mandatory. That revision is not negotiable by any
        // shipping MCP SDK (PLATFORM-CONTRACT section 5bis), but the metadata
        // profile is independent of the transport revision.
        metadataProfile: "mcp-2026-07-28",
      }),
    ],
  });
}

export type MueAuth = ReturnType<typeof buildAuth>;

export interface CreateAuthOptions {
  readonly config?: AuthConfig;
  readonly database?: DatabaseHandle;
}

export interface AuthHandle {
  readonly auth: MueAuth;
  readonly database: DatabaseHandle;
  readonly config: AuthConfig;
  close(): Promise<void>;
}

export function createAuth(options: CreateAuthOptions = {}): AuthHandle {
  const config = options.config ?? readAuthConfig();
  const database = options.database ?? createDatabase();
  const ownsDatabase = options.database === undefined;

  return {
    auth: buildAuth(config, database),
    database,
    config,
    close: async () => {
      if (ownsDatabase) await database.close();
    },
  };
}

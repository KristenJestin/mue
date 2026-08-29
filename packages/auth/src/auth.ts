import { cimd } from "@better-auth/cimd";
import { mcp } from "@better-auth/mcp";
import { betterAuthSchema, createDatabase, type DatabaseHandle } from "@mue/db";
import { betterAuth } from "better-auth";
import { drizzleAdapter } from "better-auth/adapters/drizzle";
import { bearer, jwt } from "better-auth/plugins";
import { fetchClientMetadataResource } from "./cimd-transport";
import { oauthIssuer, readAuthConfig, type AuthConfig } from "./config";
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

    // `betterAuthSchema` porte les tables Drizzle elles-mêmes, pas un nom de
    // schéma : l'adaptateur cherche chaque modèle par son nom dans cet objet et
    // laisse Drizzle émettre le SQL. Rien ici ne nomme de schéma — les tables
    // sont déclarées avec `pgTable` et atterrissent là où pointe le
    // `search_path` de la connexion (packages/db/src/client.ts) — donc pas de
    // seconde connexion, et surtout aucun endroit où Better Auth pourrait
    // désigner un schéma différent de celui où les migrations ont créé les
    // tables.
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

      /**
       * Signing keys for the access tokens an agent presents on /mcp -- and the
       * one option that decides where OAuth discovery lives.
       *
       * Better Auth reads `jwt.issuer` as *the* issuer of the whole provider: it
       * is the `issuer` field of both metadata documents, the `iss` claim of
       * every token it signs, and -- through `issuerPath` in the provider's
       * `onRequest` hook -- the paths those documents are served under. Left
       * unset it is Better Auth's own base URL, `<origin>/api/auth`, and
       * `oauthIssuer` in ./config.ts explains at length what that cost a real
       * client. Nothing else here changes: the endpoints stay under the base
       * path, because an issuer identifies a server and does not locate it.
       */
      jwt({ jwt: { issuer: oauthIssuer(config.baseUrl) } }),

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

        /**
         * RFC 7591 dynamic client registration, at `<base>/oauth2/register`.
         *
         * It is not a convenience. A shipping MCP client mints a fresh loopback
         * redirect path per session -- `http://127.0.0.1:33418/callback/UW4qso…`
         * -- and RFC 8252 section 7.3 relaxes the *port* of a loopback redirect,
         * never the path. Better Auth implements exactly that relaxation and
         * nothing wider (`findRegisteredRedirectUri`, quoted in
         * `packages/api/src/mcp/registration.ts`), so a client registered once by
         * hand is refused on its next run. Such a client can only ever work by
         * registering the URI it is about to use, which is what this endpoint is
         * for. The alternative on offer -- a Client ID Metadata Document -- needs
         * a `client_id` that is a globally routable HTTPS URL, and `./ssrf.ts`
         * refuses every address a home network has.
         *
         * `allowUnauthenticatedClientRegistration` is what makes it usable: the
         * MCP SDK's `registerClient` sends a bare JSON POST with no credential
         * of any kind, so a token- or session-backed mode would close the
         * endpoint to every client it exists for.
         *
         * That is deliberately *not* the same as leaving it open. Section 16 is
         * explicit -- "Le caractère privé du réseau ne remplace ni
         * l'authentification ni le chiffrement" -- so being on the owner's WiFi
         * authorises nothing. The endpoint is closed by
         * `packages/api/src/mcp/registration.ts` and opens only while the owner
         * has deliberately opened a pairing window, the way a device is paired.
         * Better Auth never sees a request outside one.
         */
        allowDynamicClientRegistration: true,
        allowUnauthenticatedClientRegistration: true,
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

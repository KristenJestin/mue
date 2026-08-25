import { boolean, integer, jsonb, pgSchema, text, timestamp } from "drizzle-orm/pg-core";

/**
 * Better Auth owns every table below. The shapes are transcribed from
 * `getSchema()` of better-auth 1.7.1 with the exact plugin set Mue configures
 * (bearer, jwt, @better-auth/mcp, @better-auth/cimd), so a Better Auth upgrade
 * that adds a field is a migration here and nowhere else.
 *
 * Columns stay camelCase because Better Auth addresses them by its own field
 * names; renaming them would need a `fields` mapping per model, which is one
 * more place for the two to drift. `mue_app` is snake_case, as SQL usually is.
 *
 * PRD section 20.3: the application creates no schema. `mue_auth` is
 * provisioned by the DBA (infra/README.md); the migrations only fill it.
 */
export const mueAuth = pgSchema("mue_auth");

export const user = mueAuth.table("user", {
  id: text("id").primaryKey(),
  name: text("name").notNull(),
  email: text("email").notNull().unique(),
  emailVerified: boolean("emailVerified").notNull().default(false),
  image: text("image"),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull().defaultNow(),
  updatedAt: timestamp("updatedAt", { withTimezone: true }).notNull().defaultNow(),
});

/**
 * One row per client session. The Android bearer token is a session token, so
 * one row here is one revocable device (sections 9.3 and 15.3).
 */
export const session = mueAuth.table("session", {
  id: text("id").primaryKey(),
  expiresAt: timestamp("expiresAt", { withTimezone: true }).notNull(),
  token: text("token").notNull().unique(),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull().defaultNow(),
  updatedAt: timestamp("updatedAt", { withTimezone: true }).notNull(),
  ipAddress: text("ipAddress"),
  userAgent: text("userAgent"),
  userId: text("userId")
    .notNull()
    .references(() => user.id, { onDelete: "cascade" }),
});

export const account = mueAuth.table("account", {
  id: text("id").primaryKey(),
  issuer: text("issuer").notNull(),
  accountId: text("accountId").notNull(),
  providerId: text("providerId").notNull(),
  userId: text("userId")
    .notNull()
    .references(() => user.id, { onDelete: "cascade" }),
  accessToken: text("accessToken"),
  refreshToken: text("refreshToken"),
  idToken: text("idToken"),
  accessTokenExpiresAt: timestamp("accessTokenExpiresAt", { withTimezone: true }),
  refreshTokenExpiresAt: timestamp("refreshTokenExpiresAt", { withTimezone: true }),
  scope: text("scope"),
  password: text("password"),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull().defaultNow(),
  updatedAt: timestamp("updatedAt", { withTimezone: true }).notNull(),
});

export const verification = mueAuth.table("verification", {
  id: text("id").primaryKey(),
  identifier: text("identifier").notNull(),
  value: text("value").notNull(),
  expiresAt: timestamp("expiresAt", { withTimezone: true }).notNull(),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull().defaultNow(),
  updatedAt: timestamp("updatedAt", { withTimezone: true }).notNull().defaultNow(),
});

/** Signing keys for the OAuth access tokens agents present on `/mcp`. */
export const jwks = mueAuth.table("jwks", {
  id: text("id").primaryKey(),
  publicKey: text("publicKey").notNull(),
  privateKey: text("privateKey").notNull(),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull().defaultNow(),
  expiresAt: timestamp("expiresAt", { withTimezone: true }),
  alg: text("alg"),
  crv: text("crv"),
});

/**
 * One agent identity, revocable on its own (section 21). `clientDiscoveryId`
 * is set by the CIMD plugin for a client that registered itself through a
 * Client ID Metadata Document.
 */
export const oauthClient = mueAuth.table("oauthClient", {
  id: text("id").primaryKey(),
  clientId: text("clientId").notNull().unique(),
  clientSecret: text("clientSecret"),
  clientDiscoveryId: text("clientDiscoveryId"),
  disabled: boolean("disabled").default(false),
  skipConsent: boolean("skipConsent"),
  enableEndSession: boolean("enableEndSession"),
  subjectType: text("subjectType"),
  scopes: text("scopes").array(),
  clientCredentialsScopes: text("clientCredentialsScopes").array(),
  userId: text("userId").references(() => user.id),
  createdAt: timestamp("createdAt", { withTimezone: true }),
  updatedAt: timestamp("updatedAt", { withTimezone: true }),
  name: text("name"),
  uri: text("uri"),
  icon: text("icon"),
  contacts: text("contacts").array(),
  tos: text("tos"),
  policy: text("policy"),
  softwareId: text("softwareId"),
  softwareVersion: text("softwareVersion"),
  softwareStatement: text("softwareStatement"),
  redirectUris: text("redirectUris").array().notNull(),
  postLogoutRedirectUris: text("postLogoutRedirectUris").array(),
  backchannelLogoutUri: text("backchannelLogoutUri"),
  backchannelLogoutSessionRequired: boolean("backchannelLogoutSessionRequired"),
  tokenEndpointAuthMethod: text("tokenEndpointAuthMethod"),
  applicationType: text("applicationType"),
  jwks: text("jwks"),
  jwksUri: text("jwksUri"),
  grantTypes: text("grantTypes").array(),
  responseTypes: text("responseTypes").array(),
  requirePKCE: boolean("requirePKCE"),
  dpopBoundAccessTokens: boolean("dpopBoundAccessTokens").default(false),
  referenceId: text("referenceId"),
  metadata: jsonb("metadata"),
});

export const oauthResource = mueAuth.table("oauthResource", {
  id: text("id").primaryKey(),
  identifier: text("identifier").notNull().unique(),
  name: text("name").notNull(),
  accessTokenTtl: integer("accessTokenTtl"),
  refreshTokenTtl: integer("refreshTokenTtl"),
  signingAlgorithm: text("signingAlgorithm"),
  signingKeyId: text("signingKeyId"),
  allowedScopes: text("allowedScopes").array(),
  customClaims: jsonb("customClaims"),
  dpopBoundAccessTokensRequired: boolean("dpopBoundAccessTokensRequired").default(false),
  disabled: boolean("disabled").default(false),
  createdAt: timestamp("createdAt", { withTimezone: true }),
  updatedAt: timestamp("updatedAt", { withTimezone: true }),
  policyVersion: integer("policyVersion").default(1),
  metadata: jsonb("metadata"),
});

export const oauthClientResource = mueAuth.table("oauthClientResource", {
  id: text("id").primaryKey(),
  clientId: text("clientId")
    .notNull()
    .references(() => oauthClient.clientId, { onDelete: "cascade" }),
  resourceId: text("resourceId")
    .notNull()
    .references(() => oauthResource.identifier, { onDelete: "cascade" }),
  metadata: jsonb("metadata"),
  createdAt: timestamp("createdAt", { withTimezone: true }),
});

export const oauthRefreshToken = mueAuth.table("oauthRefreshToken", {
  id: text("id").primaryKey(),
  token: text("token").notNull().unique(),
  clientId: text("clientId")
    .notNull()
    .references(() => oauthClient.clientId),
  sessionId: text("sessionId").references(() => session.id, { onDelete: "set null" }),
  userId: text("userId")
    .notNull()
    .references(() => user.id),
  referenceId: text("referenceId"),
  authorizationCodeId: text("authorizationCodeId"),
  resources: text("resources").array(),
  requestedUserInfoClaims: text("requestedUserInfoClaims").array(),
  expiresAt: timestamp("expiresAt", { withTimezone: true }).notNull(),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull(),
  revoked: timestamp("revoked", { withTimezone: true }),
  rotatedAt: timestamp("rotatedAt", { withTimezone: true }),
  rotationReplayResponse: text("rotationReplayResponse"),
  rotationReplayExpiresAt: timestamp("rotationReplayExpiresAt", { withTimezone: true }),
  authTime: timestamp("authTime", { withTimezone: true }),
  confirmation: jsonb("confirmation"),
  scopes: text("scopes").array().notNull(),
});

export const oauthAccessToken = mueAuth.table("oauthAccessToken", {
  id: text("id").primaryKey(),
  token: text("token").notNull().unique(),
  clientId: text("clientId")
    .notNull()
    .references(() => oauthClient.clientId),
  sessionId: text("sessionId").references(() => session.id, { onDelete: "set null" }),
  userId: text("userId").references(() => user.id),
  referenceId: text("referenceId"),
  authorizationCodeId: text("authorizationCodeId"),
  resources: text("resources").array(),
  requestedUserInfoClaims: text("requestedUserInfoClaims").array(),
  refreshId: text("refreshId").references(() => oauthRefreshToken.id),
  expiresAt: timestamp("expiresAt", { withTimezone: true }).notNull(),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull(),
  revoked: timestamp("revoked", { withTimezone: true }),
  confirmation: jsonb("confirmation"),
  scopes: text("scopes").array().notNull(),
});

export const oauthConsent = mueAuth.table("oauthConsent", {
  id: text("id").primaryKey(),
  clientId: text("clientId")
    .notNull()
    .references(() => oauthClient.clientId),
  userId: text("userId").references(() => user.id),
  referenceId: text("referenceId"),
  resources: text("resources").array(),
  requestedUserInfoClaims: text("requestedUserInfoClaims").array(),
  scopes: text("scopes").array().notNull(),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull(),
  updatedAt: timestamp("updatedAt", { withTimezone: true }).notNull(),
});

/** Replay store for private_key_jwt client assertions: an id and its expiry. */
export const oauthClientAssertion = mueAuth.table("oauthClientAssertion", {
  id: text("id").primaryKey(),
  expiresAt: timestamp("expiresAt", { withTimezone: true }).notNull(),
});

/**
 * The object handed to the Better Auth Drizzle adapter. Its keys are Better
 * Auth model names, not table names, and the adapter looks them up verbatim.
 */
export const betterAuthSchema = {
  user,
  session,
  account,
  verification,
  jwks,
  oauthClient,
  oauthResource,
  oauthClientResource,
  oauthRefreshToken,
  oauthAccessToken,
  oauthConsent,
  oauthClientAssertion,
} as const;

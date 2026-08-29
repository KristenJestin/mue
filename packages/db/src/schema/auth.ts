import { boolean, integer, jsonb, pgTable, text, timestamp } from "drizzle-orm/pg-core";

/**
 * Better Auth owns every table below. The shapes are transcribed from
 * `getSchema()` of better-auth 1.7.1 with the exact plugin set Mue configures
 * (bearer, jwt, @better-auth/mcp, @better-auth/cimd), so a Better Auth upgrade
 * that adds a field is a migration here and nowhere else.
 *
 * Columns stay camelCase because Better Auth addresses them by its own field
 * names; renaming them would need a `fields` mapping per model, which is one
 * more place for the two to drift. The application tables of `app.ts` are
 * snake_case, as SQL usually is.
 *
 * Ces tables vivent dans `public`, comme les autres (voir `app.ts`), et ce sont
 * elles qui portent le vrai risque du schéma partagé : `user`, `session`,
 * `account`, `verification` et `jwks` sont des noms qu'une autre application du
 * propriétaire peut déjà porter. Aucun préfixe n'est ajouté — un préfixe serait
 * un renommage que Better Auth ne suivrait pas sans une table de correspondance
 * par modèle — et c'est l'absence d'`IF NOT EXISTS` dans le `CREATE TABLE`
 * généré qui transforme une collision en échec de migration plutôt qu'en greffe
 * silencieuse sur la table d'un autre.
 */

export const user = pgTable("user", {
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
export const session = pgTable("session", {
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

export const account = pgTable("account", {
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

export const verification = pgTable("verification", {
  id: text("id").primaryKey(),
  identifier: text("identifier").notNull(),
  value: text("value").notNull(),
  expiresAt: timestamp("expiresAt", { withTimezone: true }).notNull(),
  createdAt: timestamp("createdAt", { withTimezone: true }).notNull().defaultNow(),
  updatedAt: timestamp("updatedAt", { withTimezone: true }).notNull().defaultNow(),
});

/** Signing keys for the OAuth access tokens agents present on `/mcp`. */
export const jwks = pgTable("jwks", {
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
export const oauthClient = pgTable("oauthClient", {
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

export const oauthResource = pgTable("oauthResource", {
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

export const oauthClientResource = pgTable("oauthClientResource", {
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

export const oauthRefreshToken = pgTable("oauthRefreshToken", {
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

export const oauthAccessToken = pgTable("oauthAccessToken", {
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

export const oauthConsent = pgTable("oauthConsent", {
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
export const oauthClientAssertion = pgTable("oauthClientAssertion", {
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

import type { DatabaseHandle } from "@mue/db";
import { schema } from "@mue/db";
import { desc, eq, max, sql } from "drizzle-orm";

/**
 * Section 15.3 ends on a sentence that is easy to read past: *"Avant la
 * livraison du produit Web complet, les memes revocations doivent rester
 * possibles par une commande d'administration locale documentee."*
 *
 * So the listing and revocation the Web admin will eventually offer exist here
 * first, and `scripts/admin.ts` is a thin caller. The Web product will call the
 * same four functions rather than reimplement them.
 *
 * Revocation is immediate and deletes nothing on the phone: a revoked Android
 * token stops synchronising and Room keeps every row (test 22.5).
 */

const { session, user, oauthAccessToken, oauthClient, oauthConsent, oauthRefreshToken } = schema;

export interface SessionSummary {
  readonly id: string;
  readonly userEmail: string;
  readonly createdAt: Date;
  /** Better Auth refreshes this on use, so it is the last-use column. */
  readonly lastUsedAt: Date;
  readonly expiresAt: Date;
  readonly ipAddress: string | null;
  readonly userAgent: string | null;
}

export async function listSessions(handle: DatabaseHandle): Promise<SessionSummary[]> {
  return handle.db
    .select({
      id: session.id,
      userEmail: user.email,
      createdAt: session.createdAt,
      lastUsedAt: session.updatedAt,
      expiresAt: session.expiresAt,
      ipAddress: session.ipAddress,
      userAgent: session.userAgent,
    })
    .from(session)
    .innerJoin(user, eq(session.userId, user.id))
    .orderBy(desc(session.updatedAt));
}

/**
 * Delete the session row. The token is the row, so the next request finds
 * nothing and is answered as unauthenticated -- no hint that it once existed.
 */
export async function revokeSession(handle: DatabaseHandle, sessionId: string): Promise<boolean> {
  const removed = await handle.db
    .delete(session)
    .where(eq(session.id, sessionId))
    .returning({ id: session.id });
  return removed.length > 0;
}

export interface AgentSummary {
  readonly clientId: string;
  readonly name: string | null;
  readonly scopes: readonly string[];
  readonly disabled: boolean;
  /** Discovered through a Client ID Metadata Document rather than registered. */
  readonly discovered: boolean;
  /**
   * When the client row was written -- that is, when the owner's pairing window let
   * this agent register. Better Auth leaves the column nullable, so a row created by
   * a path that did not stamp it reads as unknown rather than as the epoch.
   */
  readonly registeredAt: Date | null;
  readonly lastUsedAt: Date | null;
}

export async function listAgents(handle: DatabaseHandle): Promise<AgentSummary[]> {
  const lastUse = handle.db
    .select({
      clientId: oauthAccessToken.clientId,
      lastUsedAt: max(oauthAccessToken.createdAt).as("last_used_at"),
    })
    .from(oauthAccessToken)
    .groupBy(oauthAccessToken.clientId)
    .as("last_use");

  const rows = await handle.db
    .select({
      clientId: oauthClient.clientId,
      name: oauthClient.name,
      scopes: oauthClient.scopes,
      disabled: oauthClient.disabled,
      discoveryId: oauthClient.clientDiscoveryId,
      registeredAt: oauthClient.createdAt,
      lastUsedAt: lastUse.lastUsedAt,
    })
    .from(oauthClient)
    .leftJoin(lastUse, eq(lastUse.clientId, oauthClient.clientId))
    .orderBy(oauthClient.clientId);

  return rows.map((row) => ({
    clientId: row.clientId,
    name: row.name,
    scopes: row.scopes ?? [],
    disabled: row.disabled === true,
    discovered: row.discoveryId !== null,
    registeredAt: row.registeredAt,
    lastUsedAt: row.lastUsedAt === null ? null : new Date(row.lastUsedAt),
  }));
}

export interface AgentRevocation {
  readonly found: boolean;
  readonly accessTokensRevoked: number;
  readonly refreshTokensRevoked: number;
  readonly consentsRemoved: number;
}

/**
 * Revoke one agent identity. Three things, in this order, so no window exists
 * in which a token still works:
 *
 *  1. mark the client disabled, which refuses every new authorisation;
 *  2. stamp `revoked` on the tokens it already holds;
 *  3. drop the consent, so re-authorising asks the human again.
 *
 * The client row itself stays: section 14.7 audit rows name it, and deleting
 * it would make the audit unreadable.
 */
export async function revokeAgent(
  handle: DatabaseHandle,
  clientId: string,
): Promise<AgentRevocation> {
  const now = new Date();
  return handle.db.transaction(async (tx) => {
    const disabled = await tx
      .update(oauthClient)
      .set({ disabled: true, updatedAt: now })
      .where(eq(oauthClient.clientId, clientId))
      .returning({ clientId: oauthClient.clientId });

    if (disabled.length === 0) {
      return { found: false, accessTokensRevoked: 0, refreshTokensRevoked: 0, consentsRemoved: 0 };
    }

    const access = await tx
      .update(oauthAccessToken)
      .set({ revoked: now })
      .where(
        sql`${oauthAccessToken.clientId} = ${clientId} and ${oauthAccessToken.revoked} is null`,
      )
      .returning({ id: oauthAccessToken.id });

    const refresh = await tx
      .update(oauthRefreshToken)
      .set({ revoked: now })
      .where(
        sql`${oauthRefreshToken.clientId} = ${clientId} and ${oauthRefreshToken.revoked} is null`,
      )
      .returning({ id: oauthRefreshToken.id });

    const consents = await tx
      .delete(oauthConsent)
      .where(eq(oauthConsent.clientId, clientId))
      .returning({ id: oauthConsent.id });

    return {
      found: true,
      accessTokensRevoked: access.length,
      refreshTokensRevoked: refresh.length,
      consentsRemoved: consents.length,
    };
  });
}

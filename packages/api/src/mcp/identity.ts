import { isMueScope, type MueScope } from "@mue/auth";
import type { DatabaseHandle } from "@mue/db";
import { schema } from "@mue/db";
import { eq } from "drizzle-orm";

/**
 * Who is calling, reduced to the four things a tool is allowed to know: the
 * account whose data it may touch, the agent identity that is auditable and
 * revocable (sections 15.3 and 21), the scopes actually granted (section 15.2)
 * and the token id used to check that neither has been revoked since.
 *
 * Nothing about the agent's model, vendor or prompt appears here. Section 14.1
 * forbids depending on one and section 14.7 explicitly does not want the other.
 */
export interface AgentIdentity {
  readonly userId: string;
  readonly clientId: string;
  readonly scopes: ReadonlySet<MueScope>;
  readonly tokenId: string | null;
}

/** The claim names an access token may carry the granted scopes under. */
function readScopes(claims: Record<string, unknown>): Set<MueScope> {
  const raw = claims["scope"] ?? claims["scopes"] ?? claims["scp"];
  const values =
    typeof raw === "string"
      ? raw.split(" ")
      : Array.isArray(raw)
        ? raw.filter((value): value is string => typeof value === "string")
        : [];
  // Unknown scope names are dropped rather than carried: a scope this build does
  // not implement can only widen what a tool believes it was granted.
  return new Set(values.filter(isMueScope));
}

function readString(claims: Record<string, unknown>, ...names: readonly string[]): string | null {
  for (const name of names) {
    const value = claims[name];
    if (typeof value === "string" && value.length > 0) return value;
  }
  return null;
}

export class IdentityError extends Error {}

/**
 * `client_id` and `azp` are both read because the two spellings are both in use:
 * RFC 9068 names the first, OpenID Connect the second, and which one an
 * authorization server emits is not something a resource server should assume.
 */
export function readAgentIdentity(claims: Record<string, unknown>): AgentIdentity {
  const userId = readString(claims, "sub");
  if (userId === null) throw new IdentityError("the access token carries no subject");

  const clientId = readString(claims, "client_id", "azp", "cid");
  if (clientId === null) throw new IdentityError("the access token names no client");

  return { userId, clientId, scopes: readScopes(claims), tokenId: readString(claims, "jti") };
}

/**
 * Section 15.3: "Une identite peut etre revoquee immediatement."
 *
 * A signed access token is self-contained, so verifying it against the JWKS
 * proves only that it was issued -- not that it is still wanted. Without this
 * check a revoked agent keeps working until its token expires, which is the
 * opposite of "immediatement" and would let test 22.4's revocation case pass
 * while the agent is still reading health data.
 *
 * Two rows are consulted, because `revokeAgent` writes both: the client is
 * disabled, and every token it holds is stamped. Either one alone leaves a
 * window.
 */
export async function isAgentRevoked(
  database: DatabaseHandle,
  identity: AgentIdentity,
): Promise<boolean> {
  const clients = await database.db
    .select({ disabled: schema.oauthClient.disabled })
    .from(schema.oauthClient)
    .where(eq(schema.oauthClient.clientId, identity.clientId));

  const client = clients[0];
  // An unknown client is a revoked client: `revokeAgent` keeps the row, so the
  // only way it is missing is that it never existed or was erased outright.
  if (client === undefined || client.disabled === true) return true;

  if (identity.tokenId === null) return false;

  const rows = await database.db
    .select({ revoked: schema.oauthAccessToken.revoked })
    .from(schema.oauthAccessToken)
    .where(eq(schema.oauthAccessToken.id, identity.tokenId));

  // Only a row that exists and is stamped is conclusive. An absent row is not
  // read as revocation: whether a signed access token is also persisted, and
  // under which key, is the authorization server's business, and inferring
  // revocation from its absence would refuse every valid token the day Better
  // Auth stops storing them. Disabling the client above is what `revokeAgent`
  // relies on, and it does not depend on this row at all.
  const stored = rows[0];
  return stored !== undefined && stored.revoked !== null;
}

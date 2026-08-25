/**
 * The OAuth scopes of section 15.2.
 *
 * Deletion is a scope of its own rather than folded into a domain write.
 * Section 15.2 allows either, and the explicit permission is what lets a
 * personal configuration grant an agent full write access while still refusing
 * it the one operation FR-SYNC-005 cannot undo without a new mutation.
 *
 * `nutrition:*` is declared now and has no tools yet. Section 17 leaves the
 * food domain to a later arbitration; declaring the scope early means shipping
 * those tools will not require re-consenting every agent.
 *
 * `openid` and `offline_access` are the OAuth machinery, not Mue permissions:
 * without `offline_access` an agent gets no refresh token and section 22.4's
 * refresh test cannot pass.
 */

export const MUE_SCOPES = [
  "profile:read",
  "profile:write",
  "weight:read",
  "weight:write",
  "activity:read",
  "activity:write",
  "nutrition:read",
  "nutrition:write",
  "data:delete",
] as const;

export type MueScope = (typeof MUE_SCOPES)[number];

export const OAUTH_SCOPES = ["openid", "offline_access", ...MUE_SCOPES] as const;

/** Shown on the consent page. One line, in the words a human would use. */
export const SCOPE_DESCRIPTIONS: Readonly<Record<(typeof OAUTH_SCOPES)[number], string>> = {
  openid: "Identify the account you are signing in with",
  offline_access: "Stay connected without asking you again",
  "profile:read": "Read your height and date of birth",
  "profile:write": "Update your height and date of birth",
  "weight:read": "Read your weight measurements",
  "weight:write": "Record and update weight measurements",
  "activity:read": "Read your finished activity sessions",
  "activity:write": "Create and update activity sessions",
  "nutrition:read": "Read your food data",
  "nutrition:write": "Write your food data",
  "data:delete": "Delete synchronised data",
};

export function isMueScope(value: string): value is MueScope {
  return (MUE_SCOPES as readonly string[]).includes(value);
}

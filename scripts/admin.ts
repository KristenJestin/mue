#!/usr/bin/env bun
/**
 * Local administration for Mue Platform.
 *
 * Section 15.3 assigns the session, device and agent listing to the Web admin
 * of PRD_WEB.md, then closes with the sentence that made this file a
 * deliverable before that admin existed: *"Avant la livraison du produit Web
 * complet, les memes revocations doivent rester possibles par une commande
 * d'administration locale documentee."* Section 21 turns it into an acceptance
 * criterion -- every agent has a revocable identity.
 *
 * `apps/platform/src/routes/settings.agents.tsx` now serves the agent half of
 * that listing over the Web, and calls these same functions. This command is not
 * superseded by it and is not going away: it is the path that still works when
 * the Web shell will not build, when the session cookie is the thing that is
 * broken, or when the process is not running at all. Sessions and devices are
 * still only here.
 *
 * Usage, from the repository root:
 *
 *   bun --env-file=.env run scripts/admin.ts sessions list
 *   bun --env-file=.env run scripts/admin.ts sessions revoke <sessionId>
 *   bun --env-file=.env run scripts/admin.ts agents list
 *   bun --env-file=.env run scripts/admin.ts agents revoke <clientId>
 *
 * It reads the same `DATABASE_URL` as the platform and needs nothing running.
 * Revocation takes effect on the next request; it deletes no synchronised data
 * and no data on any phone (test 22.5).
 */
// Imported by path rather than by package name. Only a package that declares a
// workspace dependency gets the symlink, and the root manifest has a single
// owner (PLATFORM-CONTRACT section 7: two agents editing its catalog conflict).
// Internal packages are consumed as TypeScript source anyway, so this resolves
// to exactly what `@mue/auth` exports.
import { listAgents, listSessions, revokeAgent, revokeSession } from "../packages/auth/src/index";
import { createDatabase } from "../packages/db/src/index";

const USAGE = `mue admin

  sessions list                  every session, newest use first
  sessions revoke <sessionId>    drop one session: that device stops syncing
  agents list                    every OAuth client, its scopes and last use
  agents revoke <clientId>       disable an agent and revoke its live tokens
`;

function fail(message: string): never {
  console.error(message);
  console.error(`\n${USAGE}`);
  process.exit(1);
}

function when(value: Date | null): string {
  return value === null ? "never" : value.toISOString();
}

async function main(argv: readonly string[]): Promise<void> {
  const [subject, action, argument] = argv;
  if (subject === undefined || subject === "--help" || subject === "-h") {
    console.log(USAGE);
    return;
  }

  const handle = createDatabase();
  try {
    if (subject === "sessions" && action === "list") {
      const sessions = await listSessions(handle);
      if (sessions.length === 0) {
        console.log("no sessions");
        return;
      }
      for (const item of sessions) {
        console.log(
          [
            item.id,
            item.userEmail,
            `last used ${when(item.lastUsedAt)}`,
            `expires ${when(item.expiresAt)}`,
            item.ipAddress ?? "-",
            item.userAgent ?? "-",
          ].join("  "),
        );
      }
      return;
    }

    if (subject === "sessions" && action === "revoke") {
      if (argument === undefined) fail("sessions revoke needs a session id");
      const revoked = await revokeSession(handle, argument);
      console.log(revoked ? `revoked session ${argument}` : `no session ${argument}`);
      if (!revoked) process.exitCode = 1;
      return;
    }

    if (subject === "agents" && action === "list") {
      const agents = await listAgents(handle);
      if (agents.length === 0) {
        console.log("no agents");
        return;
      }
      for (const agent of agents) {
        console.log(
          [
            agent.clientId,
            agent.name ?? "-",
            agent.disabled ? "REVOKED" : "active",
            agent.discovered ? "cimd" : "registered",
            `since ${when(agent.registeredAt)}`,
            `last used ${when(agent.lastUsedAt)}`,
            agent.scopes.length === 0 ? "no scopes" : agent.scopes.join(","),
          ].join("  "),
        );
      }
      return;
    }

    if (subject === "agents" && action === "revoke") {
      if (argument === undefined) fail("agents revoke needs a client id");
      const result = await revokeAgent(handle, argument);
      if (!result.found) {
        console.log(`no agent ${argument}`);
        process.exitCode = 1;
        return;
      }
      console.log(
        `revoked ${argument}: ${result.accessTokensRevoked} access token(s), ` +
          `${result.refreshTokensRevoked} refresh token(s), ` +
          `${result.consentsRemoved} consent(s) removed`,
      );
      return;
    }

    fail(`unknown command: ${[subject, action].filter(Boolean).join(" ")}`);
  } finally {
    await handle.close();
  }
}

await main(process.argv.slice(2));

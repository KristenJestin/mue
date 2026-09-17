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
 *   bun --env-file=.env run scripts/admin.ts accounts create <email> [name]
 *
 * It reads the same `DATABASE_URL` as the platform and needs nothing running.
 * Revocation takes effect on the next request; it deletes no synchronised data
 * and no data on any phone (test 22.5).
 *
 * `accounts create` is the odd one out and arrived later. The other three read
 * or revoke; this one writes, and it exists because the Android client has no
 * sign-up screen (AGENTS.md §4.6) while the owner recreates `mue_dev` with
 * `docker compose down -v` often enough that "create the account again, by
 * hand" is a real chore. It goes through Better Auth and refuses to run against
 * anything but a development database -- `packages/auth/src/accounts.ts` argues
 * both at length.
 */
// Imported by path rather than by package name. Only a package that declares a
// workspace dependency gets the symlink, and the root manifest has a single
// owner (PLATFORM-CONTRACT section 7: two agents editing its catalog conflict).
// Internal packages are consumed as TypeScript source anyway, so this resolves
// to exactly what `@mue/auth` exports.
import {
  createApiKey,
  createDevelopmentAccount,
  listAgents,
  listApiKeys,
  listSessions,
  MIN_PASSWORD_LENGTH,
  revokeAgent,
  revokeApiKey,
  revokeSession,
} from "../packages/auth/src/index";
import { createDatabase } from "../packages/db/src/index";

/**
 * The one variable this script reads for itself.
 *
 * Named rather than reused: `BETTER_AUTH_SECRET` and `DATABASE_URL` come from
 * `.env` and belong to the server, and a password typed for one seeding run
 * belongs in neither that file nor that lifetime.
 */
const PASSWORD_VARIABLE = "MUE_ACCOUNT_PASSWORD";

const USAGE = `mue admin

  sessions list                  every session, newest use first
  sessions revoke <sessionId>    drop one session: that device stops syncing
  agents list                    every OAuth client, what it was granted and its last use
  agents revoke <clientId>       disable an agent and revoke its live tokens
  keys list [email]              every API key, revoked ones included
  keys create <label> [email]    mint an API key for an agent, and print it once
  keys revoke <keyId> [email]    refuse a key from the next request onwards
  accounts create <email> [name] seed a development account the phone can pair with

An argument is optional wherever an email is: this server holds one account, so
the commands find it. They refuse to guess once a second account exists.

${PASSWORD_VARIABLE} carries the password for "accounts create"; with a
terminal on stdin the command asks for it instead. It is never an argument --
see readPassword() for why. Minimum ${MIN_PASSWORD_LENGTH} characters.
`;

/**
 * Le compte que la commande vise, sans le demander quand il n'y a pas de doute.
 *
 * Cette base porte un seul compte humain : exiger une adresse à chaque fois
 * serait une cérémonie, et deviner serait pire le jour où il y en a deux. D'où
 * les trois issues, dans cet ordre : une adresse donnée est crue et doit
 * exister ; sans adresse, un seul compte est pris ; **deux comptes font échouer
 * la commande** au lieu d'en choisir un. Une clé créée sur le mauvais compte
 * n'est pas réparable depuis cette commande, et se découvre en production.
 */
async function soleAccount(
  handle: ReturnType<typeof createDatabase>,
  email: string | undefined,
): Promise<{ readonly id: string; readonly email: string }> {
  if (email !== undefined) {
    const rows = await handle.sql<{ id: string; email: string }[]>`
      select id, email from "user" where email = ${email} limit 1
    `;
    const found = rows[0];
    if (found === undefined) fail(`no account ${email}`);
    return found;
  }

  const rows = await handle.sql<{ id: string; email: string }[]>`
    select id, email from "user" order by "createdAt" asc limit 2
  `;
  const first = rows[0];
  if (first === undefined) {
    fail("no account on this server. Seed one first: accounts create <email> [name]");
  }
  if (rows.length > 1) {
    fail("this server holds more than one account; name one: keys create <label> <email>");
  }
  return first;
}

/**
 * Le texte d'une clé fraîchement créée, et tout ce qu'il faut pour s'en servir.
 *
 * Le jeton n'est affiché qu'ici, une fois. Il n'est pas rappelable : la base
 * n'en garde que le SHA-256, donc une clé perdue se remplace — c'est écrit à
 * l'écran plutôt que dans une documentation que personne ne relit.
 */
function createdKeyText(
  token: string,
  keyId: string,
  label: string,
  email: string,
  origin: string,
): string {
  return [
    `key ${keyId} created for ${email}, label "${label}"`,
    "",
    `  ${token}`,
    "",
    "This is the only time the token is printed: only its SHA-256 is stored, so a",
    "  lost key is replaced, never recovered.",
    "",
    "An agent presents it as a bearer header. For Hermes, on this server:",
    "",
    `    hermes config set MUE_MCP_KEY ${token}`,
    "",
    "then, in config.yaml:",
    "",
    "    mcp_servers:",
    "      mue:",
    `        url: ${origin}/mcp`,
    "        headers:",
    '          Authorization: "Bearer ${MUE_MCP_KEY}"',
  ].join("\n");
}

/**
 * Where the password comes in, and the one place it must never come in from.
 *
 * **Not an argument.** `accounts create <email> <password>` would be shorter to
 * type and is refused, because an argument is the one channel the person typing
 * it does not control:
 *
 *   - it is written verbatim into `~/.bash_history`, `~/.zsh_history` or
 *     `ConsoleHost_history.txt`, which are plain files that outlive the run and
 *     that nothing prompts anyone to clean;
 *   - it is in `argv`, so it shows in `ps`, in `/proc/<pid>/cmdline`, and in
 *     Task Manager's command-line column, to every other process on the machine
 *     for as long as this one lives;
 *   - it is on screen, which matters on the machine where a screen is shared.
 *
 * A password that leaked that way is not a local inconvenience: it is the
 * credential the phone pairs with, and re-using it anywhere else makes it worse.
 *
 * So, in order:
 *
 * 1. **${PASSWORD_VARIABLE}**, when it is set. An environment variable is not
 *    secret either -- it is readable by the process's own children and, on
 *    Linux, through `/proc/<pid>/environ` by the same user -- but *who puts it
 *    there is the caller's decision*: `read -rs` then `export`, a password
 *    manager's `op run`, or a file that is not the shell history. An argument
 *    offers no such choice. This is the path a script takes.
 * 2. **the terminal**, when stdin is one: asked for, read without echo, and
 *    never written anywhere. This is the path a human takes, and it is the only
 *    one where the password touches nothing at all.
 * 3. **neither**, which is a refusal rather than a read of piped stdin. A
 *    command whose password source depends on whether it was run in a pipeline
 *    is a command whose failure mode is a password read from a log file.
 *
 * Raw mode is put back on every way out of this function, or the terminal is
 * left without an echo for every command typed after this one. `finally` covers
 * the ordinary paths and the Ctrl-C branch restores it by hand, because `fail`
 * ends the process and `process.exit` does not run `finally` blocks.
 */
async function readPassword(): Promise<string> {
  const fromEnvironment = process.env[PASSWORD_VARIABLE];
  if (fromEnvironment !== undefined && fromEnvironment !== "") return fromEnvironment;

  if (!process.stdin.isTTY) {
    fail(
      `no password. Set ${PASSWORD_VARIABLE}, or run this from a terminal so it can ask.\n` +
        "It is deliberately not an argument: see readPassword() in this file.",
    );
  }

  process.stdout.write("Password (not echoed): ");
  process.stdin.setRawMode(true);
  process.stdin.resume();
  try {
    // Bytes and not characters: a UTF-8 password arrives in several of them,
    // and decoding at the end is the only way the accent is the one that was
    // typed. Backspace drops one byte, which is right for everything a keyboard
    // sends as one -- and this is a password prompt, not a text editor.
    const typed: number[] = [];
    for await (const chunk of process.stdin as AsyncIterable<Uint8Array>) {
      for (const byte of chunk) {
        if (byte === 3) {
          // Restored here and not in `finally`: `fail` ends the process, and
          // `process.exit` does not run `finally` blocks. Without these two
          // lines, Ctrl-C at this prompt leaves the shell echoing nothing --
          // for every command typed after it, not just this one.
          process.stdin.setRawMode(false);
          process.stdin.pause();
          process.stdout.write("\n");
          fail("cancelled.");
        }
        if (byte === 13 || byte === 10) {
          process.stdout.write("\n");
          return new TextDecoder().decode(Uint8Array.from(typed));
        }
        if (byte === 127 || byte === 8) {
          typed.pop();
          continue;
        }
        typed.push(byte);
      }
    }
    process.stdout.write("\n");
    return new TextDecoder().decode(Uint8Array.from(typed));
  } finally {
    process.stdin.setRawMode(false);
    process.stdin.pause();
  }
}

function fail(message: string): never {
  console.error(message);
  console.error(`\n${USAGE}`);
  process.exit(1);
}

function when(value: Date | null): string {
  return value === null ? "never" : value.toISOString();
}

async function main(argv: readonly string[]): Promise<void> {
  const [subject, action, argument, name] = argv;
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
            // What it holds, not what it may ask for: a dynamic registration is
            // stamped with the server's whole allowed set, so `agent.scopes` says
            // almost nothing about any one agent.
            agent.grantedScopes.length === 0
              ? "granted nothing"
              : `granted ${agent.grantedScopes.join(",")}`,
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

    if (subject === "keys" && action === "list") {
      const account = await soleAccount(handle, argument);
      const keys = await listApiKeys(handle, account.id);
      if (keys.length === 0) {
        console.log(`no keys for ${account.email}`);
        return;
      }
      for (const key of keys) {
        console.log(
          [
            key.id,
            key.label,
            key.revokedAt === null ? "active" : "REVOKED",
            `created ${when(key.createdAt)}`,
            `last used ${when(key.lastUsedAt)}`,
          ].join("  "),
        );
      }
      return;
    }

    if (subject === "keys" && action === "create") {
      if (argument === undefined) fail("keys create needs a label");
      const account = await soleAccount(handle, name);
      const created = await createApiKey(handle, { userId: account.id, label: argument });
      console.log(
        createdKeyText(
          created.token,
          created.key.id,
          created.key.label,
          account.email,
          (process.env.BETTER_AUTH_URL ?? "https://<origin>").replace(/\/+$/, ""),
        ),
      );
      return;
    }

    if (subject === "keys" && action === "revoke") {
      if (argument === undefined) fail("keys revoke needs a key id");
      const account = await soleAccount(handle, name);
      const revoked = await revokeApiKey(handle, account.id, argument);
      console.log(revoked ? `revoked key ${argument}` : `no live key ${argument}`);
      if (!revoked) process.exitCode = 1;
      return;
    }

    if (subject === "accounts" && action === "create") {
      if (argument === undefined) fail("accounts create needs an email address");
      const password = await readPassword();

      // `createDevelopmentAccount` refuses a non-development database before it
      // reads anything else, and refuses a short password before it writes
      // anything. Both come back as an `Error` whose message is written to be
      // read by whoever typed the command -- it names the database, or the
      // minimum length -- so it is printed as it is and the stack trace is
      // dropped. A trace above that sentence adds nothing and hides it.
      let result;
      try {
        result = await createDevelopmentAccount(handle, {
          email: argument,
          password,
          ...(name === undefined ? {} : { name }),
        });
      } catch (error) {
        console.error(error instanceof Error ? error.message : String(error));
        process.exitCode = 1;
        return;
      }

      console.log(
        result.created
          ? `created ${result.email} (${result.userId}). Pair the phone with it; there is no\n` +
              "  sign-up screen in the Android client, which is why this command exists."
          : `${result.email} already exists (${result.userId}): nothing written, password\n` +
              "  unchanged. This command is safe to run again.",
      );
      return;
    }

    fail(`unknown command: ${[subject, action].filter(Boolean).join(" ")}`);
  } finally {
    await handle.close();
  }
}

await main(process.argv.slice(2));

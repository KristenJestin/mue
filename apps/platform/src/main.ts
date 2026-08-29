import { runtime } from "./serve";
import { isDelegatedPath } from "./server";
import { readTlsFiles, schemeFor } from "./tls";

/**
 * The process entry: `bun run dist/server/main.js`, and nothing else.
 *
 * It is a second module rather than a block at the bottom of `src/serve.ts` because
 * Bun starts a server of its own for any file it runs whose default export looks like
 * one, and Start's server entry has to default-export `{ fetch }`. Two servers then
 * raced for the same port and the process died on `EADDRINUSE` before answering a
 * single request. This module exports nothing, so only the `Bun.serve` below listens.
 *
 * It reads `PORT`, `HOST`, `MUE_CLIENT_DIR` and the two TLS paths of `./tls`; `serve.ts`
 * and the packages under it read everything else. No test imports either, so `bun test`
 * still runs with `DATABASE_URL` unset.
 */

const port = Number(process.env["PORT"] ?? 3000);

// Loopback by default: no Mue service listens on a public interface unless it was
// configured to (PRD section 22.5).
const hostname = process.env["HOST"] ?? "127.0.0.1";

/**
 * Absent unless both variables are set, and it throws rather than downgrade if only one
 * is -- see `./tls`. Read before `Bun.serve` so a half-configured certificate stops the
 * process here, with a message, instead of opening a plaintext port that answers
 * `/health/ready` and looks healthy.
 */
const tlsFiles = readTlsFiles(process.env);

/**
 * Spread into the options below rather than written as `tls: undefined`.
 *
 * `exactOptionalPropertyTypes` is on, and Bun's `tls` is optional but not nullable, so an
 * explicit `undefined` is a type error rather than an absence. Spreading `{}` is the only
 * shape that means what this needs to mean: for a deployment that sets neither variable,
 * the key is not there at all and the options object is the one that shipped before.
 */
const tlsOption =
  tlsFiles === undefined
    ? {}
    : {
        tls: {
          // `BunFile`, not a string: Bun reads the PEM off disk itself, and the paths in the
          // failure it raises for a missing or unreadable file are the ones from `.env`.
          cert: Bun.file(tlsFiles.certificateFile),
          key: Bun.file(tlsFiles.keyFile),
        },
      };

/**
 * Section 20.5: "Les assets TanStack Start et le serveur Hono sont livrés dans la
 * même image." `vite build` writes the browser bundle to `dist/client` and this file
 * to `dist/server`, so the client directory is the sibling of the running module.
 *
 * Nothing here hard-codes where Vite puts the bundle. The emitted names carry a
 * content hash and the directory has already moved once (`/_build` to `/assets`)
 * between the plugin default and what Vite+ actually emits; a prefix written here
 * would be a second source of truth that only fails in production. Disk decides.
 */
const clientDirectory = new URL(process.env["MUE_CLIENT_DIR"] ?? "../client/", import.meta.url);

async function serveAsset(pathname: string): Promise<Response | null> {
  // `new URL(relative, base)` normalises `..` before we look, so a traversal attempt
  // leaves the client directory and is rejected here rather than opened.
  const candidate = new URL(pathname.slice(1), clientDirectory);
  if (!candidate.href.startsWith(clientDirectory.href)) return null;

  const file = Bun.file(candidate);
  if (!(await file.exists())) return null;
  return new Response(file, {
    // Every emitted name carries a content hash, so the bytes behind a URL never
    // change. Nothing here is personal: it is the same bundle for every visitor.
    headers: { "cache-control": "public, max-age=31536000, immutable" },
  });
}

/**
 * How long a connection may say nothing before Bun closes it.
 *
 * Bun's default is ten seconds, which is right for request/response and wrong for
 * `GET /api/v1/sync/events` — the live channel of sync PRD 9.4 holds one response
 * open for as long as the phone is in the foreground and writes only when the
 * journal moves. Left at the default it died at ten seconds every time, and the
 * failure was invisible from the client: the greeting arrived, so the connection
 * looked healthy, and the phone simply stopped hearing about changes until its
 * socket timed out and it reconnected.
 *
 * Sixty seconds is three times the channel's own heartbeat
 * (`HEARTBEAT_INTERVAL_MS` in `@mue/api`), so two lost comments are tolerated
 * before the transport gives up, and it is the same order as the idle timeout a
 * reverse proxy in front of this process would apply anyway (section 20.5). It is
 * not infinite: a connection that has genuinely gone away must still be reclaimed,
 * and it is the heartbeat that proves one has not.
 */
const IDLE_TIMEOUT_SECONDS = 60;

/**
 * Une ligne par requête, et ce qu'elle ne dit surtout pas.
 *
 * Le serveur n'en écrivait aucune. Trois `console` dans tout le dépôt — démarrage, arrêt, et
 * une panne de synchronisation non rattrapée — ce qui veut dire qu'un `400` de Better Auth sur
 * un corps mal formé ne produisait rien du tout : la requête arrivait, échouait, et le journal
 * restait vide. On cherche alors du côté du réseau une panne qui est dans le corps du message.
 *
 * Ce qui est écrit : la méthode, le chemin, le code de retour, la durée. Rien d'autre, et c'est
 * délibéré.
 *
 * - **Pas de corps.** Il porte un mot de passe sur `/api/auth/sign-in/email` et des données de
 *   santé sur `/api/v1/sync/push`. Un journal est un fichier qui traîne, se copie et se
 *   sauvegarde ; ce qui n'y entre pas ne peut pas en sortir.
 * - **Pas d'en-têtes.** `Authorization` porte le jeton de session, `Cookie` la session Web.
 *   Journaliser un jeton revient à l'écrire en clair sur le disque, ce que même le TLS ne
 *   rattraperait pas.
 * - **Pas de chaîne de requête.** PRD_SERVER_SYNC_MCP 16 interdit d'y mettre une donnée
 *   personnelle, mais un journal ne doit pas dépendre du respect d'une règle écrite ailleurs.
 * - **Pas d'adresse d'appelant.** Un seul utilisateur, sur son réseau : elle n'apprendrait rien
 *   et serait une donnée de plus à protéger.
 *
 * Les sondes de santé réussies sont muettes : le `healthcheck` de Compose en émet une toutes
 * les trente secondes, et un journal noyé sous 2 880 lignes par jour est un journal que
 * personne ne lit. Une sonde qui échoue, elle, est exactement ce qu'on veut voir.
 */
function logRequest(method: string, pathname: string, status: number, elapsedMs: number): void {
  if (status < 400 && pathname.startsWith("/health/")) return;
  console.log(`${method} ${pathname} ${status} ${elapsedMs}ms`);
}

/**
 * Le routage, extrait de `fetch` pour que la journalisation voie le code de retour de tous les
 * chemins — l'asset servi depuis le disque comme la réponse de Start.
 */
async function handle(request: Request, pathname: string): Promise<Response> {
  /**
   * The delegated prefixes are asked first and are never looked for on disk. That
   * is the boundary of section 20.2, restated where it can be violated: a file
   * dropped into the client bundle must not be able to answer for `/api/*`,
   * `/mcp`, `/health/*` or `/.well-known/*`.
   */
  if (!isDelegatedPath(pathname) && (request.method === "GET" || request.method === "HEAD")) {
    const asset = await serveAsset(pathname);
    if (asset !== null) return asset;
  }
  // Wrapped rather than passed by reference: Bun hands its `Server` as the second
  // argument, and Start reads a request-options object there.
  return runtime.entry.fetch(request);
}

const server = Bun.serve({
  port,
  hostname,
  idleTimeout: IDLE_TIMEOUT_SECONDS,
  ...tlsOption,
  fetch: async (request) => {
    const startedAt = Date.now();
    const { pathname } = new URL(request.url);
    const response = await handle(request, pathname);
    logRequest(request.method, pathname, response.status, Date.now() - startedAt);
    return response;
  },
});

// The scheme comes from the configuration that was actually read, not from a literal:
// the line is the only place anyone checks whether TLS came up, so it must not be able
// to say `https` about a plaintext port.
console.log(`Mue Platform listening on ${schemeFor(tlsFiles)}://${server.hostname}:${server.port}`);

/**
 * Section 20.5: "L'arrêt et le redémarrage ne perdent aucune mutation acquittée."
 * `stop(true)` lets in-flight requests finish before the pool goes away, so a push
 * that has already been answered is never cut off mid-commit.
 */
let stopping = false;
const shutdown = async (signal: NodeJS.Signals): Promise<void> => {
  if (stopping) return;
  stopping = true;
  console.log(`${signal} received, draining.`);
  await server.stop(true);
  await runtime.close();
  process.exit(0);
};

process.on("SIGINT", (signal) => void shutdown(signal));
process.on("SIGTERM", (signal) => void shutdown(signal));

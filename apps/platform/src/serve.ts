import entry from "./server";

const port = Number(process.env["PORT"] ?? 3000);

// Loopback by default: no Mue service listens on a public interface unless it was
// configured to (PRD section 22.5).
const hostname = process.env["HOST"] ?? "127.0.0.1";

// Wrapped rather than passed by reference: Bun hands its `Server` as the second
// argument, and Start reads a request-options object there.
const server = Bun.serve({ port, hostname, fetch: (request) => entry.fetch(request) });

console.log(`Mue Platform listening on http://${server.hostname}:${server.port}`);

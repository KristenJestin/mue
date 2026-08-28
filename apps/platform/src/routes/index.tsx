import { Link, createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import type { ReactElement } from "react";

/**
 * `/` -- "une page minimale après connexion confirmant que la plateforme fonctionne"
 * (PRD_WEB section 5).
 *
 * It confirms it by asking, from the browser, the one question that crosses the seam
 * PRD section 20.2 defines: `/health/ready` is answered by Hono, this page is rendered
 * by TanStack Start, and both come out of the same origin and the same process. If the
 * badge below says the database is reachable, the delegation is working end to end.
 *
 * There is no session state here on purpose. Reading one would take a server function,
 * and a server function is a module that runs on both sides of the compiler; the shell
 * has nothing private to show yet, so section 15.1's boundary stays a boundary that no
 * code has to be trusted to respect. The dashboards that do need it belong to
 * `PRD_WEB.md`.
 */

type Readiness = "checking" | "ready" | "not_ready" | "unreachable";

const WORDING: Readonly<Record<Readiness, string>> = {
  checking: "Checking the server…",
  ready: "The server is up and its database is reachable.",
  not_ready: "The server is up, but a dependency is not answering.",
  unreachable: "The server did not answer.",
};

export const Route = createFileRoute("/")({ component: HomePage });

function HomePage(): ReactElement {
  const [readiness, setReadiness] = useState<Readiness>("checking");

  useEffect(() => {
    const abort = new AbortController();
    fetch("/health/ready", { signal: abort.signal })
      .then(async (response) => (await response.json()) as { status?: string })
      .then((report) => {
        setReadiness(report.status === "ready" ? "ready" : "not_ready");
      })
      .catch(() => {
        // An aborted fetch is a component unmounting, not a server that is down. The
        // state is dropped either way, so both cases take the same branch.
        if (!abort.signal.aborted) setReadiness("unreachable");
      });
    return () => {
      abort.abort();
    };
  }, []);

  return (
    <main>
      <h1>Mue Platform</h1>
      <p>{WORDING[readiness]}</p>
      <p>
        This server holds your weight, activity and health data, synchronises it with the Mue app on
        your phone, and lets an agent you have authorised read and write it over MCP.
      </p>
      <p>
        <Link to="/sign-in">Sign in</Link> to link this browser to it.
      </p>
      <p>
        <Link to="/settings/agents">Settings → Agents</Link> is where you open the pairing window
        for a new agent, see the ones already registered, and revoke one.
      </p>
    </main>
  );
}

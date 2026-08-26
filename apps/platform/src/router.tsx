import { createRouter } from "@tanstack/react-router";
import { routeTree } from "./routeTree.gen";

/**
 * The router entry, reached as `#tanstack-router-entry`.
 *
 * Both halves of Start import it: the browser bundle hydrates the router this builds,
 * and `createStartHandler` calls `getRouter()` once per server-rendered request. So
 * everything reachable from here is reachable from the browser, and nothing that reads
 * a secret may be imported into this graph -- section 15.1. `src/client-bundle.test.ts`
 * is what holds that line.
 *
 * The factory shape is required: a module-scope router would be shared by every
 * concurrent request on the server and would leak one visitor's state into another's.
 */
export function getRouter() {
  return createRouter({
    routeTree,
    // The shell is three pages and no data loading; preloading on intent costs nothing
    // and removes the blank frame between `/` and `/sign-in`.
    defaultPreload: "intent",
    scrollRestoration: true,
  });
}

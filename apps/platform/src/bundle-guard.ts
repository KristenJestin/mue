/**
 * What the two bundles are not allowed to contain, and the predicates that say so.
 *
 * This is a *build-output* check, not a configuration check, and that distinction is
 * the whole point. The externals list in `vite.config.ts` is an intention; a bundle is
 * a fact. Both hazards below were introduced by an externals list that looked right.
 *
 * The functions are pure and live in `src/` so `bundle-guard.test.ts` can exercise them
 * with `DATABASE_URL` unset. `vite.config.ts` is their only importer, so nothing here
 * reaches either bundle.
 */

export interface ForbiddenMarker {
  /** What was found, in the words of the thing that must not be there. */
  readonly name: string;
  readonly pattern: RegExp;
  /** Why it is forbidden, printed with the failure. */
  readonly why: string;
}

/**
 * Markers of a module that must stay outside the server bundle.
 *
 * `import.meta.main` is first because it is the *class* of the bug rather than one
 * instance of it. A module scope that a bundler merges into the entry inherits the
 * entry's `import.meta.main`, so any `if (import.meta.main)` CLI guard anywhere in the
 * graph becomes code that runs at boot. `packages/db/src/migrate.ts` ends in exactly
 * that guard, and inlining `@mue/db` turned the deployment's migration step into
 * something every starting replica did to itself, concurrently -- which PRD section
 * 20.3 forbids in as many words: "Les migrations sont exécutées explicitement pendant
 * le déploiement, jamais concurremment par chaque processus au démarrage."
 *
 * No source file of this package uses `import.meta.main`: `src/main.ts` is the process
 * entry and needs no guard, because nothing imports it. So any occurrence in the SSR
 * output arrived from a dependency that should have been external.
 *
 * The remaining markers name the migrator itself, so the failure says *which* CLI came
 * along rather than only that one did.
 */
export const SERVER_BUNDLE_FORBIDDEN: readonly ForbiddenMarker[] = [
  {
    name: "import.meta.main",
    pattern: /import\.meta\.main/,
    why:
      "a bundled CLI guard runs at boot: module scopes merge, so the entry's " +
      "`import.meta.main` becomes the inlined module's. Add the package to " +
      "SERVER_EXTERNALS in vite.config.ts.",
  },
  {
    name: "the @mue/db migration runner",
    pattern: /__mue_migrations|-->\s*statement-breakpoint|pg_advisory_lock/,
    why:
      "PRD section 20.3 runs migrations explicitly at deploy, never at process start. " +
      "`@mue/db` must stay external.",
  },
];

/** Every forbidden marker present in `code`, in declaration order. */
export function findForbidden(
  code: string,
  markers: readonly ForbiddenMarker[] = SERVER_BUNDLE_FORBIDDEN,
): ForbiddenMarker[] {
  return markers.filter((marker) => marker.pattern.test(code));
}

/**
 * Section 15.1: "Aucun client ne reçoit le secret maître Better Auth."
 *
 * The check is only possible when the secret is in the build environment, which is the
 * case that matters: a build that cannot see the secret cannot inline it. `undefined`
 * and the empty string are therefore not failures, and neither is a short placeholder
 * -- `readAuthConfig` refuses anything under 32 characters, so a shorter value is not
 * a secret and matching on it would flag ordinary words.
 */
export const MINIMUM_SECRET_LENGTH = 32;

export function leaksSecret(code: string, secret: string | undefined): boolean {
  if (secret === undefined || secret.length < MINIMUM_SECRET_LENGTH) return false;
  return code.includes(secret);
}

/** The failure text, kept here so the test can assert it names the file and the cause. */
export function describeForbidden(fileName: string, markers: readonly ForbiddenMarker[]): string {
  return markers.map((marker) => `${fileName} contains ${marker.name}: ${marker.why}`).join("\n");
}

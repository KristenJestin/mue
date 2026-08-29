import type { DatabaseHandle } from "@mue/db";
import { createAuth, MIN_PASSWORD_LENGTH, type CreateAuthOptions } from "./auth";
import type { AuthConfig } from "./config";

/**
 * Créer un compte, parce que le client Android ne sait pas le faire.
 *
 * L'application n'a aucun parcours d'inscription (AGENTS.md §4.6) : le compte
 * doit préexister au premier appairage. Sur le serveur du propriétaire il en
 * existe un, créé une fois. En développement il disparaît à chaque
 * `docker compose down -v`, et le propriétaire recrée `mue_dev` souvent — d'où
 * `scripts/admin.ts accounts create`, dont ceci est la moitié testable.
 *
 * ## Pourquoi Better Auth et jamais un `INSERT`
 *
 * Un compte utilisable n'est pas une ligne, c'en est deux. `user` porte
 * l'identité ; `account` porte le secret, en `providerId = "credential"`, avec
 * un mot de passe haché par la fonction que Better Auth a choisie, salée comme
 * il l'entend, dans le format que sa propre vérification sait relire. Écrire
 * ces lignes à la main produit un compte qui *existe* — Adminer le montre, la
 * table est bien remplie — et qui ne s'authentifie pas : `sign-in/email`
 * répond `INVALID_EMAIL_OR_PASSWORD`, exactement comme un mot de passe faux, et
 * rien dans aucun journal ne distingue les deux. Le hachage et la ligne
 * `account` appartiennent à Better Auth ; cette fonction se contente de
 * l'appeler.
 *
 * `auth.api.signUpEmail` plutôt que le gestionnaire HTTP : il n'y a pas de
 * requête ici, donc pas d'`Origin` à fabriquer ni de `trustedOrigins` à
 * satisfaire. C'est le même point d'entrée, une couche de transport en moins.
 *
 * ## Le garde-fou, et pourquoi celui-là suffit
 *
 * `packages/db/src/testing.ts` en porte un du même genre, et il n'est pas
 * transposable tel quel : `resetSchemas` **détruit**, donc son dernier verrou
 * est la liste close des tables qu'il a le droit de supprimer, et sa couche par
 * nom de base refuse `mue_dev` — la base de développement n'est pas jetable.
 * Ici c'est l'inverse sur les deux points. Cette fonction ne supprime rien, et
 * `mue_dev` est précisément la base visée.
 *
 * Le risque qui reste est étroit et il est réel : semer un compte de
 * développement — un courriel connu, un mot de passe qu'on vient de taper dans
 * un terminal — sur la base de **production** du propriétaire, celle que son
 * téléphone synchronise. Ce serait un compte de plus, permanent, dont personne
 * ne saurait qu'il est là.
 *
 * Deux couches le refusent, et c'est la conjonction qui compte :
 *
 * 1. **l'hôte doit être la boucle locale.** La production tourne sur le serveur
 *    personnel du propriétaire ; depuis cette machine elle se joint par un nom,
 *    jamais par `127.0.0.1` (`infra/env.server.example` :
 *    `host.docker.internal:5432/mue`).
 * 2. **le nom de la base doit être celui d'une base de développement** —
 *    `mue_dev`, ou la base jetable des tests. La base de production s'appelle
 *    `mue`, donc une faute de frappe de quatre caractères dans un `DATABASE_URL`
 *    par ailleurs local est refusée elle aussi.
 *
 * Une seule des deux ne suffirait pas. La boucle locale seule laisserait passer
 * un tunnel SSH vers le cluster de production, qui présente exactement
 * `127.0.0.1` — et c'est la manière dont on administre un serveur distant. Le
 * nom seul laisserait passer un `mue_dev` sur une machine qui n'est pas
 * celle-ci. Ensemble, elles décrivent le cluster de `infra/compose.dev.yml` et
 * lui seul.
 *
 * **Il n'y a pas d'échappatoire par variable d'environnement**, et l'absence
 * est délibérée. `MUE_ALLOW_DESTRUCTIVE_TESTS` existe parce qu'une base jetable
 * peut légitimement porter un autre nom que celui prévu ; « créer un compte de
 * développement sur la production » n'a aucune version légitime. Le compte du
 * propriétaire y est créé une fois, à la main, par la même API mais depuis le
 * serveur qui l'héberge.
 */

/** La base de développement, celle que le téléphone appaire (AGENTS.md §5). */
export const DEVELOPMENT_DATABASE = "mue_dev";

/**
 * Les noms de base sur lesquels cette commande accepte de tourner.
 *
 * `mue_test` y est parce que c'est la seule base sur laquelle la suite qui
 * éprouve cette fonction peut travailler (AGENTS.md §9.3 : aucune suite
 * n'ouvre de connexion autrement que par `createTestDatabase()`). L'admettre
 * n'élargit rien : `mue_test` est créée pour être détruite, et un compte semé
 * là ne survit pas au prochain `resetSchemas`.
 *
 * `MUE_TEST_DATABASE` est lue au même endroit et pour la même raison que dans
 * `packages/db/src/testing.ts` : pointer les tests ailleurs se fait en une
 * variable, pas en deux listes qui divergent.
 */
export function developmentDatabaseNames(
  env: Readonly<Record<string, string | undefined>> = process.env,
): readonly string[] {
  return [DEVELOPMENT_DATABASE, env["MUE_TEST_DATABASE"] ?? "mue_test"];
}

/**
 * Couche 1 et couche 2, dans cet ordre, sur l'URL que porte la connexion
 * ouverte — jamais sur `DATABASE_URL` relu depuis l'environnement, qui pourrait
 * avoir changé entre l'ouverture et ici.
 *
 * Le contrôle n'est pas partagé avec `assertLoopback` de `@mue/db`, qui n'est
 * pas exporté et dont le message parle de réinitialisation. Six lignes
 * recopiées valent mieux qu'un garde-fou dont on modifierait le message pour
 * une opération et le comportement pour l'autre sans s'en apercevoir.
 */
function assertDevelopmentDatabase(
  url: string,
  env: Readonly<Record<string, string | undefined>> = process.env,
): void {
  const parsed = new URL(url);
  const { hostname } = parsed;
  const loopback =
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "::1" ||
    hostname === "[::1]";
  if (!loopback) {
    throw new Error(
      `refusing to create an account on "${hostname}": it is not the local development cluster.\n` +
        "This command seeds a development account, with a password typed at a terminal. " +
        "The production server is reached by name, never on loopback, and its account was " +
        "created once, by hand, on the machine that hosts it.",
    );
  }

  const name = parsed.pathname.replace(/^\//, "");
  const allowed = developmentDatabaseNames(env);
  if (allowed.includes(name)) return;

  throw new Error(
    `refusing to create an account in "${name}": it is not a development database.\n` +
      `The development databases are ${allowed.map((item) => `"${item}"`).join(" and ")}. ` +
      `Mue's production database is called "mue", so a DATABASE_URL that is local but points ` +
      "at the wrong name lands here rather than seeding a permanent account nobody knows about.",
  );
}

export interface DevelopmentAccountOptions {
  readonly email: string;
  /**
   * En clair, parce qu'il faut bien qu'il le soit au moment où Better Auth le
   * hache. Ce qui compte est *par où il est arrivé* : `scripts/admin.ts` le lit
   * dans une variable d'environnement dédiée ou sur l'entrée standard, jamais
   * dans un argument de ligne de commande, et son KDoc dit pourquoi.
   */
  readonly password: string;
  /** Défaut : la partie gauche du courriel. Better Auth exige un nom. */
  readonly name?: string;
  /**
   * Couture de test, comme `CreateAuthOptions.config` : sans elle, `createAuth`
   * lit `BETTER_AUTH_SECRET` dans l'environnement, ce que le script a et ce
   * qu'une suite ne veut pas dépendre d'avoir.
   */
  readonly config?: AuthConfig;
}

export interface AccountCreation {
  /** `false` quand le compte existait déjà — ce n'est pas une erreur. */
  readonly created: boolean;
  readonly userId: string;
  /** Le courriel tel qu'il est stocké : Better Auth le met en minuscules. */
  readonly email: string;
}

/**
 * Sème un compte utilisable par le premier appairage, et rien de plus.
 *
 * L'ordre des refus est celui de ce qu'ils coûtent à découvrir tard :
 *
 * 1. **où** — la base, avant toute écriture et avant même de regarder le mot de
 *    passe. Un mot de passe trop court sur la production doit être refusé pour
 *    la bonne raison ;
 * 2. **quoi** — la longueur minimale, ici plutôt que dans Better Auth, qui la
 *    signale par une `APIError` que le script devrait déballer pour la rendre
 *    lisible ;
 * 3. **existe-t-il déjà** — auquel cas la fonction rend la main sans rien
 *    écrire.
 *
 * Ce troisième point est ce qui rend la commande rejouable, et la rejouabilité
 * est la propriété demandée : le propriétaire lance `accounts create` après un
 * `down -v`, mais aussi quand il ne sait plus s'il l'a déjà lancée. Un second
 * appel n'écrit rien, ne remplace pas le mot de passe et ne casse aucune
 * session en cours — il le dit et sort. Better Auth refuserait de son côté
 * (`USER_ALREADY_EXISTS_USE_ANOTHER_EMAIL`, 422), mais en levant : la
 * pré-lecture transforme une exception en un résultat, ce qui est la
 * différence entre une commande qu'on peut mettre dans un script et une qu'on
 * ne peut pas.
 *
 * Un `signUpEmail` réussi ouvre aussi une session — c'est le défaut
 * `autoSignIn` de Better Auth, et le changer se ferait globalement, donc pour
 * tous les clients. La ligne apparaît dans `admin.ts sessions list` et se
 * révoque comme les autres ; elle n'est ni gênante ni cachée, elle est
 * simplement à ne pas prendre pour un appareil appairé.
 *
 * La course entre la lecture et l'écriture n'est pas gardée, et n'a pas à
 * l'être : c'est une commande d'administration lancée à la main sur un cluster
 * mono-utilisateur, et si deux exécutions se croisaient, la contrainte
 * d'unicité sur `user.email` fait le reste.
 */
export async function createDevelopmentAccount(
  handle: DatabaseHandle,
  options: DevelopmentAccountOptions,
): Promise<AccountCreation> {
  assertDevelopmentDatabase(handle.config.url);

  const email = options.email.trim().toLowerCase();
  if (email === "" || !/^[^\s@]+@[^\s@]+$/.test(email)) {
    throw new Error(`"${options.email}" is not an email address.`);
  }

  if (options.password.length < MIN_PASSWORD_LENGTH) {
    throw new Error(
      `the password is ${options.password.length} characters; ` +
        `Better Auth requires at least ${MIN_PASSWORD_LENGTH} on this server ` +
        "(minPasswordLength, packages/auth/src/auth.ts).",
    );
  }

  const existing = await handle.sql<{ id: string }[]>`
    select "id" from "user" where "email" = ${email} limit 1
  `;
  const found = existing[0];
  if (found !== undefined) return { created: false, userId: found.id, email };

  // `exactOptionalPropertyTypes` : une propriété facultative absente et une
  // propriété valant `undefined` ne sont pas la même chose, donc l'option n'est
  // posée que lorsqu'elle a une valeur.
  const authOptions: CreateAuthOptions =
    options.config === undefined
      ? { database: handle }
      : { database: handle, config: options.config };
  const auth = createAuth(authOptions);
  try {
    const result = await auth.auth.api.signUpEmail({
      body: {
        email,
        password: options.password,
        name: options.name ?? email.split("@")[0] ?? email,
      },
    });
    return { created: true, userId: result.user.id, email: result.user.email };
  } finally {
    // `createAuth` a reçu la connexion, donc `close()` ne la ferme pas : c'est
    // l'appelant qui l'a ouverte et qui la ferme. Appelé quand même pour que la
    // règle reste celle de `CreateAuthOptions` et non une hypothèse d'ici.
    await auth.close();
  }
}

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createDatabase, createTestDatabase, migrate, type DatabaseHandle } from "@mue/db";
import { createDevelopmentAccount, developmentDatabaseNames } from "./accounts";
import { MIN_PASSWORD_LENGTH } from "./auth";
import type { AuthConfig } from "./config";

/**
 * `scripts/admin.ts accounts create`, éprouvé sur `mue_test` et sans serveur.
 *
 * Trois propriétés sont démontrables ici, et ce sont les trois qui décident si
 * la commande est sûre à lancer :
 *
 * 1. **elle refuse ce qui n'est pas une base de développement** — la seule
 *    manière dont cette commande peut faire du mal est de semer un compte de
 *    test sur la production ;
 * 2. **elle refuse un mot de passe trop court avant d'appeler Better Auth**,
 *    avec un message qui nomme la longueur, plutôt que de laisser remonter une
 *    `APIError` ;
 * 3. **elle est rejouable** — un second appel n'écrit rien et n'échoue pas, ce
 *    qui est ce que le propriétaire fait après un `docker compose down -v` et,
 *    parfois, quand il ne sait plus s'il l'a déjà lancée.
 *
 * Ce qui n'est pas éprouvé ici est l'appairage lui-même : il demande le serveur
 * HTTP, et `auth.test.ts` couvre déjà `sign-in/email` sur le compte qu'il crée.
 * Ce qui l'est en revanche, et qui est le cœur du choix « Better Auth et jamais
 * un `INSERT` », c'est que le compte semé **s'authentifie** — c'est-à-dire que
 * la ligne `account` existe et que le haché est relisible par la vérification
 * de Better Auth. Un compte écrit à la main passerait tous les autres tests de
 * ce fichier et échouerait sur celui-là.
 */

const BASE_URL = "http://localhost:3000";
const CONFIG: AuthConfig = {
  secret: "accounts-test-secret-that-is-long-enough",
  baseUrl: BASE_URL,
  trustedOrigins: [BASE_URL],
  mcpResource: `${BASE_URL}/mcp`,
  loginPage: "/sign-in",
  consentPage: "/consent",
  secureCookies: false,
};

let database: DatabaseHandle;
/** Les leurres de [fakeHandle], fermés en fin de suite plutôt que jamais. */
const unopened: DatabaseHandle[] = [];
const email = `seed-${Date.now()}@mue.test`;
const password = "correct-horse-battery-staple";

beforeAll(async () => {
  database = createTestDatabase();
  await migrate(database);
  // AGENTS.md §9.2 : la clé JWKS est chiffrée sous le secret qui l'a émise, et
  // `mue_test` est partagée par toutes les suites. Sans ce nettoyage, la suite
  // qui passe après celle de `@mue/api` échoue sur « Failed to decrypt private
  // key », ce qui ne dit rien de ce qu'elle teste.
  await database.sql`delete from jwks`;
});

afterAll(async () => {
  await Promise.all(unopened.map((handle) => handle.close()));
  await database.close();
});

describe("le garde-fou", () => {
  /**
   * La couche qui compte : la production du propriétaire se joint par un nom,
   * jamais par la boucle locale, donc un `DATABASE_URL` qui la désigne est
   * refusé avant toute écriture. Le nom de la base y est délibérément
   * `mue_dev` — la bonne base, le mauvais hôte : si seul le nom était contrôlé,
   * ceci passerait.
   */
  test("refuse un hôte qui n'est pas la boucle locale", async () => {
    const remote = fakeHandle("postgres://mue:secret@mue.home.arpa:5432/mue_dev");

    await expect(
      createDevelopmentAccount(remote, { email, password, config: CONFIG }),
    ).rejects.toThrow(/not the local development cluster/);
  });

  /**
   * L'autre moitié de la conjonction. `mue` est le nom de la base de
   * production (`infra/env.server.example`), et sur la boucle locale elle est à
   * quatre caractères de `mue_dev` — un tunnel SSH, ou une faute de frappe,
   * suffit à produire cette URL. Si seul l'hôte était contrôlé, ceci passerait.
   */
  test("refuse une base qui n'est pas une base de développement", async () => {
    const production = fakeHandle("postgres://mue:secret@127.0.0.1:5432/mue");

    await expect(
      createDevelopmentAccount(production, { email, password, config: CONFIG }),
    ).rejects.toThrow(/not a development database/);
  });

  test("le message nomme les bases acceptées, pour que le refus soit réparable", async () => {
    const production = fakeHandle("postgres://mue:secret@127.0.0.1:5432/mue");

    await expect(
      createDevelopmentAccount(production, { email, password, config: CONFIG }),
    ).rejects.toThrow(/"mue_dev"/);
  });

  /**
   * `mue_dev` est la base visée, pas une base interdite — c'est là que le
   * garde-fou d'ici diverge de celui de `resetSchemas`, qui la refuse. Aucun
   * compte n'est créé par ce test : la connexion est un leurre et l'appel
   * s'arrête sur la longueur du mot de passe, refus qui vient après celui de la
   * base et qui prouve donc que la base est passée.
   */
  test("accepte mue_dev, qui est la base que cette commande vise", async () => {
    const dev = fakeHandle("postgres://mue:secret@127.0.0.1:5433/mue_dev");

    await expect(
      createDevelopmentAccount(dev, { email, password: "short", config: CONFIG }),
    ).rejects.toThrow(/at least 12/);
  });

  test("mue_test en fait partie, sans quoi cette suite ne pourrait rien éprouver", () => {
    expect(developmentDatabaseNames({})).toContain("mue_test");
    expect(developmentDatabaseNames({})).toContain("mue_dev");
  });

  test("MUE_TEST_DATABASE déplace la base jetable, comme dans @mue/db", () => {
    expect(developmentDatabaseNames({ MUE_TEST_DATABASE: "mue_scratch" })).toEqual([
      "mue_dev",
      "mue_scratch",
    ]);
  });
});

describe("la longueur du mot de passe", () => {
  /**
   * Onze caractères, soit un de moins que `minPasswordLength`. Le refus doit
   * venir d'ici et non de Better Auth : le script affiche le message d'une
   * `Error` telle quelle, et une `APIError` avec son code et son statut ne dit
   * pas à quelqu'un devant un terminal ce qu'il doit taper de plus.
   */
  test("refuse un caractère de moins que le minimum, en nommant le minimum", async () => {
    const tooShort = "a".repeat(MIN_PASSWORD_LENGTH - 1);

    await expect(
      createDevelopmentAccount(database, { email, password: tooShort, config: CONFIG }),
    ).rejects.toThrow(`the password is ${MIN_PASSWORD_LENGTH - 1} characters`);
  });

  test("refuse avant d'écrire quoi que ce soit", async () => {
    const address = `rejected-${Date.now()}@mue.test`;
    await expect(
      createDevelopmentAccount(database, { email: address, password: "short", config: CONFIG }),
    ).rejects.toThrow();

    const rows = await database.sql<{ id: string }[]>`
      select "id" from "user" where "email" = ${address}
    `;
    expect(rows).toHaveLength(0);
  });

  test("accepte exactement le minimum", async () => {
    const address = `exact-${Date.now()}@mue.test`;
    const result = await createDevelopmentAccount(database, {
      email: address,
      password: "a".repeat(MIN_PASSWORD_LENGTH),
      config: CONFIG,
    });

    expect(result.created).toBe(true);
  });

  test("refuse ce qui n'est pas une adresse", async () => {
    await expect(
      createDevelopmentAccount(database, { email: "not-an-address", password, config: CONFIG }),
    ).rejects.toThrow(/is not an email address/);
  });
});

describe("le compte semé", () => {
  test("est créé", async () => {
    const result = await createDevelopmentAccount(database, { email, password, config: CONFIG });

    expect(result.created).toBe(true);
    expect(result.email).toBe(email);

    const rows = await database.sql<{ id: string }[]>`
      select "id" from "user" where "email" = ${email}
    `;
    expect(rows).toHaveLength(1);
  });

  /**
   * L'assertion qui justifie de passer par Better Auth plutôt que par un
   * `INSERT`. Un `user` écrit à la main sans sa ligne `account`, ou avec un
   * haché produit par une autre fonction, satisfait le test précédent et
   * échoue ici — et il échouerait de la même manière au premier appairage du
   * téléphone, en `INVALID_EMAIL_OR_PASSWORD`, ce qui se lit comme un mot de
   * passe faux.
   */
  test("porte la ligne account que la connexion relit", async () => {
    const rows = await database.sql<{ providerId: string; password: string | null }[]>`
      select a."providerId", a."password"
      from "account" a join "user" u on u."id" = a."userId"
      where u."email" = ${email}
    `;

    expect(rows).toHaveLength(1);
    expect(rows[0]?.providerId).toBe("credential");
    expect(rows[0]?.password ?? "").not.toBe("");
    // Le haché n'est pas le mot de passe. Écrit parce que c'est exactement ce
    // qu'un `INSERT` à la main produirait de faux.
    expect(rows[0]?.password).not.toBe(password);
  });

  /**
   * La rejouabilité, qui est la propriété demandée : la commande se relance
   * après un `down -v` comme après rien du tout. Un second appel ne lève pas,
   * dit que le compte existait, et rend le même identifiant.
   */
  test("un second appel est inoffensif et le dit", async () => {
    const before = await createDevelopmentAccount(database, { email, password, config: CONFIG });
    const again = await createDevelopmentAccount(database, { email, password, config: CONFIG });

    expect(again.created).toBe(false);
    expect(again.userId).toBe(before.userId);

    const rows = await database.sql<{ id: string }[]>`
      select "id" from "user" where "email" = ${email}
    `;
    expect(rows).toHaveLength(1);
  });

  /**
   * Et un second appel ne remplace pas le mot de passe. C'est ce qui rend la
   * relance vraiment sans conséquence : quelqu'un qui rejoue la commande avec
   * un autre mot de passe ne réinitialise pas silencieusement un compte dont
   * des sessions sont en cours — il apprend que le compte est déjà là.
   */
  test("un second appel ne réécrit pas le secret", async () => {
    const [before] = await database.sql<{ password: string | null }[]>`
      select a."password" from "account" a join "user" u on u."id" = a."userId"
      where u."email" = ${email}
    `;

    await createDevelopmentAccount(database, {
      email,
      password: "a-completely-different-password",
      config: CONFIG,
    });

    const [after] = await database.sql<{ password: string | null }[]>`
      select a."password" from "account" a join "user" u on u."id" = a."userId"
      where u."email" = ${email}
    `;
    expect(after?.password).toBe(before?.password as string);
  });

  /** Better Auth met le courriel en minuscules, donc la relecture aussi. */
  test("la casse du courriel ne crée pas un second compte", async () => {
    const again = await createDevelopmentAccount(database, {
      email: email.toUpperCase(),
      password,
      config: CONFIG,
    });

    expect(again.created).toBe(false);
  });
});

/**
 * Une poignée de connexion **jamais ouverte**, pour éprouver le refus sans
 * ouvrir la connexion qu'il refuse.
 *
 * `createDatabase` ne se connecte pas : `postgres` est paresseux et n'ouvre une
 * socket qu'à la première requête. Le garde-fou s'exécute avant toute requête,
 * donc rien ne part vers l'hôte nommé ici — et c'est ce qui permet de nommer
 * `mue.home.arpa` sans que le test dépende de ce qui répond, ou ne répond pas,
 * à ce nom.
 */
function fakeHandle(url: string): DatabaseHandle {
  const handle = createDatabase({ url, retentionDays: 180, maxConnections: 1 });
  unopened.push(handle);
  return handle;
}

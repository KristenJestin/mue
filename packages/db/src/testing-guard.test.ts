import { afterEach, describe, expect, test } from "bun:test";
import { resetSchemas } from "./testing";

/**
 * Ce fichier n'ouvre aucune connexion : chaque cas doit échouer *avant* que
 * `resetSchemas` ne parle à PostgreSQL, et un faux objet sans `sql` le prouve —
 * si un garde-fou passait, le test échouerait sur un `sql` indéfini plutôt que
 * de réussir en silence.
 */
function handleFor(url: string) {
  return { config: { url } } as never;
}

const OVERRIDE = "MUE_ALLOW_DESTRUCTIVE_TESTS";

afterEach(() => {
  delete process.env[OVERRIDE];
});

describe("le garde-fou de resetSchemas", () => {
  test("refuse la base de développement, celle qu'un téléphone appaire", async () => {
    await expect(
      resetSchemas(handleFor("postgres://mue:x@127.0.0.1:5433/mue_dev")),
    ).rejects.toThrow(/not a disposable database/);
  });

  test("nomme la base et dit quoi faire", async () => {
    await expect(
      resetSchemas(handleFor("postgres://mue:x@127.0.0.1:5433/mue_dev")),
    ).rejects.toThrow(/mue_dev/);
  });

  /**
   * `postgres` était sur la liste des bases jetables, et devait en sortir avec
   * le passage au schéma partagé.
   *
   * Elle y était sans danger tant que seules les tables de `mue_app` et
   * `mue_auth` étaient supprimées : ces schémas n'existent pas dans la base
   * d'administration d'un cluster ordinaire, donc la fonction n'y trouvait rien.
   * Depuis que Mue vit dans le schéma partagé, viser `postgres` viserait le
   * schéma courant d'une base que le propriétaire partage entre ses
   * applications.
   */
  test("refuse la base d'administration du cluster", async () => {
    await expect(
      resetSchemas(handleFor("postgres://mue:x@127.0.0.1:5433/postgres")),
    ).rejects.toThrow(/not a disposable database/);
  });

  test("refuse un hôte qui n'est pas la boucle locale, avant même de regarder le nom", async () => {
    await expect(
      resetSchemas(handleFor("postgres://mue:x@db.internal:5432/mue_test")),
    ).rejects.toThrow(/non-loopback/);
  });

  /**
   * L'échappatoire relâche le *nom de la base*, et rien d'autre. Elle ne peut
   * pas faire sortir la fonction de la boucle locale : le cluster de production
   * du propriétaire est à distance, et c'est la seule chose qui l'en sépare
   * quand quelqu'un a déjà décidé de forcer le passage.
   */
  test("l'échappatoire ne relâche pas le contrôle de l'hôte", async () => {
    process.env[OVERRIDE] = "yes-destroy-it";
    await expect(resetSchemas(handleFor("postgres://mue:x@db.internal:5432/mue"))).rejects.toThrow(
      /non-loopback/,
    );
  });

  test("une valeur approchante n'ouvre pas l'échappatoire", async () => {
    process.env[OVERRIDE] = "yes";
    await expect(
      resetSchemas(handleFor("postgres://mue:x@127.0.0.1:5433/mue_dev")),
    ).rejects.toThrow(/not a disposable database/);
  });
});

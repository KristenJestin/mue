import { describe, expect, test } from "bun:test";
import { CLEARTEXT_ACKNOWLEDGEMENT, CLEARTEXT_VARIABLE, readAuthConfig } from "./config";

/**
 * Le contrôle de schéma de `BETTER_AUTH_URL`, et ce que son échappatoire relâche exactement.
 *
 * `readAuthConfig` prend l'environnement en argument : ces tests n'ont donc besoin ni de
 * `process.env`, ni de base, ni de réseau.
 */

const SECRET = "a-secret-that-is-at-least-32-characters";

function env(extra: Record<string, string>): Record<string, string> {
  return { BETTER_AUTH_SECRET: SECRET, ...extra };
}

describe("BETTER_AUTH_URL et le trafic en clair", () => {
  test("une origine en clair hors boucle locale est refusée par défaut", () => {
    expect(() => readAuthConfig(env({ BETTER_AUTH_URL: "http://192.168.1.200:8032" }))).toThrow(
      /must be https outside loopback/,
    );
  });

  test("le message de refus nomme l'échappatoire et sa valeur", () => {
    // Un refus qui ne dit pas comment passer outre envoie lire le code source.
    try {
      readAuthConfig(env({ BETTER_AUTH_URL: "http://192.168.1.200:8032" }));
      throw new Error("aurait dû refuser");
    } catch (error) {
      const message = (error as Error).message;
      expect(message).toContain(CLEARTEXT_VARIABLE);
      expect(message).toContain(CLEARTEXT_ACKNOWLEDGEMENT);
    }
  });

  test("l'échappatoire, écrite en entier, l'autorise", () => {
    const config = readAuthConfig(
      env({
        BETTER_AUTH_URL: "http://192.168.1.200:8032",
        [CLEARTEXT_VARIABLE]: CLEARTEXT_ACKNOWLEDGEMENT,
      }),
    );
    expect(config.baseUrl).toBe("http://192.168.1.200:8032");
  });

  test("une valeur approchante ne suffit pas", () => {
    // `true`, `1` ou `yes` sont ce qu'on pose sans réfléchir ; la phrase entière ne l'est pas.
    for (const value of ["true", "1", "yes", "yes-in-clear", CLEARTEXT_ACKNOWLEDGEMENT + "!"]) {
      expect(() =>
        readAuthConfig(
          env({ BETTER_AUTH_URL: "http://192.168.1.200:8032", [CLEARTEXT_VARIABLE]: value }),
        ),
      ).toThrow(/must be https outside loopback/);
    }
  });

  test("la boucle locale reste exempte sans rien poser", () => {
    for (const origin of ["http://localhost:3000", "http://127.0.0.1:3000"]) {
      expect(readAuthConfig(env({ BETTER_AUTH_URL: origin })).baseUrl).toBe(origin);
    }
  });
});

describe("secureCookies suit le schéma servi", () => {
  test("une origine en clair ne marque pas le cookie Secure", () => {
    // La règle qui compte : un cookie `Secure` sur une origine `http://` n'est jamais envoyé,
    // et la panne serait une session qui ne s'ouvre pas, sans erreur.
    const config = readAuthConfig(
      env({
        BETTER_AUTH_URL: "http://192.168.1.200:8032",
        [CLEARTEXT_VARIABLE]: CLEARTEXT_ACKNOWLEDGEMENT,
      }),
    );
    expect(config.secureCookies).toBe(false);
  });

  test("une origine chiffrée le marque, échappatoire posée ou non", () => {
    const withEscape = readAuthConfig(
      env({
        BETTER_AUTH_URL: "https://mue.example",
        [CLEARTEXT_VARIABLE]: CLEARTEXT_ACKNOWLEDGEMENT,
      }),
    );
    const without = readAuthConfig(env({ BETTER_AUTH_URL: "https://mue.example" }));
    expect(withEscape.secureCookies).toBe(true);
    expect(without.secureCookies).toBe(true);
  });

  test("la boucle locale en clair ne le marque pas non plus", () => {
    expect(readAuthConfig(env({ BETTER_AUTH_URL: "http://localhost:3000" })).secureCookies).toBe(
      false,
    );
  });
});

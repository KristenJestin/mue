import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { createTestDatabase, migrate, schema, type DatabaseHandle } from "@mue/db";
import { eq } from "drizzle-orm";
import {
  API_KEY_PREFIX,
  createApiKey,
  hashApiKeyToken,
  isApiKeyToken,
  listApiKeys,
  resolveApiKey,
  revokeApiKey,
} from "./api-keys";

/**
 * Les clés d'API de `/mcp`, éprouvées sur `mue_test` et sans serveur HTTP.
 *
 * Ce qui est démontrable ici est exactement ce qui décide si la fonctionnalité
 * est sûre : **le jeton en clair n'existe nulle part après l'avoir affiché**,
 * une clé révoquée cesse de résoudre à l'instant, et deux clés ne se confondent
 * jamais. Le reste — que `/mcp` accepte l'en-tête — appartient à `@mue/api`.
 *
 * Ce fichier n'invente aucun utilisateur : il en écrit un, par `insert`, et
 * c'est délibéré. `createDevelopmentAccount` existe pour semer un compte
 * *utilisable* (haché par Better Auth), ce dont ces tests n'ont pas besoin —
 * la clé ne dépend que d'une ligne `user` à qui se rattacher. Le compte semé
 * est supprimé en fin de suite, et la cascade emporte ses clés.
 */

let database: DatabaseHandle;
const userId = `api-keys-user-${Date.now()}`;
const email = `api-keys-${Date.now()}@mue.test`;

beforeAll(async () => {
  database = createTestDatabase();
  await migrate(database);
  await database.db.insert(schema.user).values({
    id: userId,
    name: "API keys test",
    email,
    emailVerified: true,
  });
});

afterAll(async () => {
  // La cascade de `mcp_key` fait le reste : un compte supprimé n'a plus de clés.
  await database.db.delete(schema.user).where(eq(schema.user.id, userId));
  await database.close();
});

/** La colonne telle qu'elle est stockée, sans passer par le type Drizzle. */
async function storedHash(keyId: string): Promise<string | undefined> {
  const rows = await database.sql<{ token_hash: string }[]>`
    select token_hash from mcp_key where id = ${keyId}
  `;
  return rows[0]?.token_hash;
}

describe("fabriquer une clé", () => {
  test("le jeton porte le préfixe et n'est jamais stocké", async () => {
    const created = await createApiKey(database, { userId, label: "coach" });

    expect(created.token.startsWith(API_KEY_PREFIX)).toBe(true);
    // 32 octets en base64url : 43 caractères, sans remplissage.
    expect(created.token.slice(API_KEY_PREFIX.length)).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(created.key.label).toBe("coach");
    expect(created.key.revokedAt).toBeNull();
    expect(created.key.lastUsedAt).toBeNull();

    const stored = await storedHash(created.key.id);
    expect(stored).toBe(hashApiKeyToken(created.token));
    expect(stored).not.toBe(created.token);
  });

  test("l'empreinte est un SHA-256 hexadécimal, et deux clés ne se confondent pas", async () => {
    const first = await createApiKey(database, { userId, label: "un" });
    const second = await createApiKey(database, { userId, label: "deux" });

    expect(first.token).not.toBe(second.token);
    expect(first.key.id).not.toBe(second.key.id);
    expect(await storedHash(first.key.id)).toMatch(/^[0-9a-f]{64}$/);

    const resolvedFirst = await resolveApiKey(database, first.token);
    const resolvedSecond = await resolveApiKey(database, second.token);
    expect(resolvedFirst?.keyId).toBe(first.key.id);
    expect(resolvedSecond?.keyId).toBe(second.key.id);
  });

  test("un libellé vide ou trop long est refusé avant toute écriture", async () => {
    await expect(createApiKey(database, { userId, label: "   " })).rejects.toThrow(/libellé/);
    await expect(createApiKey(database, { userId, label: "x".repeat(61) })).rejects.toThrow(
      /60 caractères/,
    );

    const keys = await listApiKeys(database, userId);
    expect(keys.some((key) => key.label.trim() === "")).toBe(false);
    expect(keys.every((key) => key.label.length <= 60)).toBe(true);
  });
});

describe("résoudre un jeton", () => {
  test("un jeton inconnu, ou qui n'a pas le préfixe, ne résout rien", async () => {
    expect(await resolveApiKey(database, `${API_KEY_PREFIX}${"A".repeat(43)}`)).toBeNull();
    expect(await resolveApiKey(database, "pas-une-cle")).toBeNull();
    expect(await resolveApiKey(database, "")).toBeNull();
    // Le préfixe seul n'est pas un jeton.
    expect(isApiKeyToken(API_KEY_PREFIX)).toBe(false);
  });

  test("last_used_at est écrit au premier usage, puis laissé tranquille", async () => {
    const created = await createApiKey(database, { userId, label: "usage" });
    const first = new Date("2026-01-01T10:00:00Z");

    await resolveApiKey(database, created.token, first);
    const after = await listApiKeys(database, userId);
    const key = after.find((candidate) => candidate.id === created.key.id);
    expect(key?.lastUsedAt?.toISOString()).toBe(first.toISOString());

    // Un second appel dans la fenêtre ne réécrit pas la colonne : c'est ce qui
    // évite une écriture par appel d'outil.
    await resolveApiKey(database, created.token, new Date(first.getTime() + 60_000));
    const still = (await listApiKeys(database, userId)).find(
      (candidate) => candidate.id === created.key.id,
    );
    expect(still?.lastUsedAt?.toISOString()).toBe(first.toISOString());

    // Au-delà de la fenêtre, la colonne suit.
    const later = new Date(first.getTime() + 6 * 60_000);
    await resolveApiKey(database, created.token, later);
    const moved = (await listApiKeys(database, userId)).find(
      (candidate) => candidate.id === created.key.id,
    );
    expect(moved?.lastUsedAt?.toISOString()).toBe(later.toISOString());
  });
});

describe("révoquer une clé", () => {
  test("la révocation est immédiate, et le jeton ne dit plus rien", async () => {
    const created = await createApiKey(database, { userId, label: "à révoquer" });
    expect(await resolveApiKey(database, created.token)).not.toBeNull();

    expect(await revokeApiKey(database, userId, created.key.id)).toBe(true);
    expect(await resolveApiKey(database, created.token)).toBeNull();
  });

  test("révoquer deux fois dit la vérité : la seconde ne trouve rien de vivant", async () => {
    const created = await createApiKey(database, { userId, label: "deux fois" });

    expect(await revokeApiKey(database, userId, created.key.id)).toBe(true);
    expect(await revokeApiKey(database, userId, created.key.id)).toBe(false);
  });

  test("révoquer une clé ne touche pas les autres", async () => {
    const doomed = await createApiKey(database, { userId, label: "condamnée" });
    const survivor = await createApiKey(database, { userId, label: "survivante" });

    await revokeApiKey(database, userId, doomed.key.id);

    expect(await resolveApiKey(database, doomed.token)).toBeNull();
    expect(await resolveApiKey(database, survivor.token)).not.toBeNull();
  });

  test("une clé d'un autre compte n'est pas révocable depuis celui-ci", async () => {
    const otherUser = `api-keys-other-${Date.now()}`;
    await database.db.insert(schema.user).values({
      id: otherUser,
      name: "Autre compte",
      email: `api-keys-other-${Date.now()}@mue.test`,
      emailVerified: true,
    });
    const foreign = await createApiKey(database, { userId: otherUser, label: "d'un autre" });

    expect(await revokeApiKey(database, userId, foreign.key.id)).toBe(false);
    expect(await resolveApiKey(database, foreign.token)).not.toBeNull();

    await database.db.delete(schema.user).where(eq(schema.user.id, otherUser));
  });

  test("une clé révoquée reste listée : l'audit la nomme encore", async () => {
    const created = await createApiKey(database, { userId, label: "gardée" });
    await revokeApiKey(database, userId, created.key.id);

    const keys = await listApiKeys(database, userId);
    const revoked = keys.find((candidate) => candidate.id === created.key.id);
    expect(revoked).toBeDefined();
    expect(revoked?.revokedAt).not.toBeNull();
  });
});

describe("lister les clés d'un compte", () => {
  test("la plus récente d'abord, et les vivantes comme les révoquées", async () => {
    const fresh = `ordre-${Date.now()}`;
    await database.db.insert(schema.user).values({
      id: fresh,
      name: "Ordre",
      email: `ordre-${Date.now()}@mue.test`,
      emailVerified: true,
    });

    const older = new Date("2026-02-01T09:00:00Z");
    const newer = new Date("2026-02-02T09:00:00Z");
    await createApiKey(database, { userId: fresh, label: "ancienne", now: older });
    const second = await createApiKey(database, { userId: fresh, label: "récente", now: newer });
    await revokeApiKey(database, fresh, second.key.id);

    const keys = await listApiKeys(database, fresh);
    expect(keys.map((key) => key.label)).toEqual(["récente", "ancienne"]);
    expect(keys[0]?.revokedAt).not.toBeNull();
    expect(keys[1]?.revokedAt).toBeNull();

    await database.db.delete(schema.user).where(eq(schema.user.id, fresh));
  });
});

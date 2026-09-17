import { createHash, randomBytes, randomUUID } from "node:crypto";
import type { DatabaseHandle } from "@mue/db";
import { schema } from "@mue/db";
import { desc, eq, sql } from "drizzle-orm";

/**
 * La clé d'API de `/mcp` : ce qui remplace, pour un agent, toute la mécanique
 * OAuth (enregistrement dynamique, fenêtre d'appairage, page de consentement).
 *
 * ## Ce que ce module est, et ce qu'il n'est pas
 *
 * Il ne connaît ni HTTP, ni Bearer, ni MCP : il fabrique un jeton, le retrouve
 * à partir de sa chaîne, le révoque, et enumère. Le garde de `/mcp`
 * (`@mue/api`) lit l'en-tête et appelle {@link resolveApiKey} ; la commande
 * d'administration et la page de réglages appellent les trois autres. Aucune
 * règle de permission n'est ici : une clé vaut le jeu de portées complet, et
 * c'est `packages/db/src/schema/app.ts` qui dit pourquoi et ce que cela coûte.
 *
 * ## Le format du jeton, et pourquoi le préfixe compte
 *
 * `mue_` suivi de 32 octets aléatoires en base64url. Le préfixe a deux usages,
 * tous les deux opérationnels :
 *
 *  - il rend un jeton reconnaissable à l'œil dans un fichier de configuration,
 *    un journal ou une conversation — la différence entre « il y a un secret
 *    ici » et « il y a une chaîne qui ressemble à un identifiant » ;
 *  - il permet au garde de refuser tout ce qui n'est pas une clé de Mue
 *    **avant** de toucher la base : le jeton de session d'un téléphone ou un
 *    JWT d'agent ne déclenche jamais un `SELECT` sur `mcp_key`.
 *
 * 32 octets plutôt que 16 : la clé n'expire pas et vaut le compte entier, donc
 * la seule protection qui reste est l'imprévisibilité. Un SHA-256 suffit à la
 * stocker — voir la table.
 */

/** Le préfixe de tout jeton de clé. Comparé tel quel, jamais par motif. */
export const API_KEY_PREFIX = "mue_";

/** Assez d'entropie pour qu'une clé permanente soit hors de portée d'une recherche. */
const TOKEN_BYTES = 32;

/**
 * Au-delà, on n'écrit plus `last_used_at` à chaque appel d'outil, seulement à
 * l'expiration de ce délai. La page de réglages affiche « vue récemment », pas
 * « vue à la milliseconde » : une écriture par appel d'outil serait un coût
 * permanent pour une information que personne ne lit à cette résolution.
 */
const LAST_USE_INTERVAL_MS = 5 * 60_000;

/** Un libellé est une étiquette d'écran, pas un document. */
export const MAX_API_KEY_LABEL_LENGTH = 60;

export interface ApiKeySummary {
  readonly id: string;
  readonly label: string;
  readonly createdAt: Date;
  /** Null tant que la clé n'a jamais servi. */
  readonly lastUsedAt: Date | null;
  /** Null pour une clé vivante ; l'instant de la révocation sinon. */
  readonly revokedAt: Date | null;
}

export interface CreatedApiKey {
  /** Le jeton en clair. Affiché une fois, à la création, et jamais relu. */
  readonly token: string;
  readonly key: ApiKeySummary;
}

/** Vrai pour une chaîne qui *prétend* être une clé de Mue. Ne prouve rien de plus. */
export function isApiKeyToken(value: string): boolean {
  return value.startsWith(API_KEY_PREFIX) && value.length > API_KEY_PREFIX.length;
}

/** L'empreinte stockée en base. Exportée pour que les tests regardent la colonne telle quelle. */
export function hashApiKeyToken(token: string): string {
  return createHash("sha256").update(token, "utf8").digest("hex");
}

function mintToken(): string {
  return `${API_KEY_PREFIX}${randomBytes(TOKEN_BYTES).toString("base64url")}`;
}

function readLabel(label: string): string {
  const trimmed = label.trim();
  if (trimmed === "") throw new Error("une clé a besoin d'un libellé");
  if (trimmed.length > MAX_API_KEY_LABEL_LENGTH) {
    throw new Error(`le libellé fait au plus ${MAX_API_KEY_LABEL_LENGTH} caractères`);
  }
  return trimmed;
}

/**
 * Fabrique une clé et rend son jeton en clair, **une seule fois**.
 *
 * L'appelant est responsable de l'afficher à la personne et de ne rien en
 * écrire : la base ne garde que l'empreinte, donc un jeton perdu se remplace, il
 * ne se retrouve pas.
 */
export async function createApiKey(
  handle: DatabaseHandle,
  options: { readonly userId: string; readonly label: string; readonly now?: Date },
): Promise<CreatedApiKey> {
  const token = mintToken();
  const now = options.now ?? new Date();

  const rows = await handle.db
    .insert(schema.mcpKey)
    .values({
      id: randomUUID(),
      userId: options.userId,
      label: readLabel(options.label),
      tokenHash: hashApiKeyToken(token),
      createdAt: now,
    })
    .returning({
      id: schema.mcpKey.id,
      label: schema.mcpKey.label,
      createdAt: schema.mcpKey.createdAt,
      lastUsedAt: schema.mcpKey.lastUsedAt,
      revokedAt: schema.mcpKey.revokedAt,
    });

  const key = rows[0];
  // `returning` sur un `insert` d'une ligne : l'absence n'est atteignable qu'en
  // cassant le pilote, et la signaler vaut mieux que rendre `undefined`.
  if (key === undefined) throw new Error("la clé n'a pas été écrite");

  return { token, key };
}

/** Les clés d'un compte, la plus récente d'abord. Les révoquées y sont, et pour cause. */
export async function listApiKeys(
  handle: DatabaseHandle,
  userId: string,
): Promise<readonly ApiKeySummary[]> {
  return handle.db
    .select({
      id: schema.mcpKey.id,
      label: schema.mcpKey.label,
      createdAt: schema.mcpKey.createdAt,
      lastUsedAt: schema.mcpKey.lastUsedAt,
      revokedAt: schema.mcpKey.revokedAt,
    })
    .from(schema.mcpKey)
    .where(eq(schema.mcpKey.userId, userId))
    .orderBy(desc(schema.mcpKey.createdAt));
}

/**
 * Révoque une clé en l'horodatant, et dit si une clé **vivante** de ce compte a
 * été trouvée.
 *
 * La ligne reste : `agent_audit` nomme l'identifiant de la clé, et un audit qui
 * désigne une ligne effacée ne dit plus qui a écrit quoi. Une seconde
 * révocation rend donc `false` — la clé est déjà révoquée — sans être une
 * erreur : le propriétaire qui clique deux fois n'a rien cassé.
 */
export async function revokeApiKey(
  handle: DatabaseHandle,
  userId: string,
  keyId: string,
  now: Date = new Date(),
): Promise<boolean> {
  const revoked = await handle.db
    .update(schema.mcpKey)
    .set({ revokedAt: now })
    .where(
      sql`${schema.mcpKey.id} = ${keyId} and ${schema.mcpKey.userId} = ${userId} and ${schema.mcpKey.revokedAt} is null`,
    )
    .returning({ id: schema.mcpKey.id });

  return revoked.length > 0;
}

export interface ResolvedApiKey {
  readonly keyId: string;
  readonly userId: string;
}

/**
 * Le point d'entrée du garde : un jeton présenté devient un compte, ou rien.
 *
 * « Rien » recouvre quatre cas qui doivent être indistinguables pour l'appelant
 * — chaîne qui n'a pas le préfixe, empreinte inconnue, clé révoquée, en-tête
 * vide. Le garde n'a aucun intérêt à savoir lequel, et un agent encore moins :
 * une clé révoquée doit apprendre à celui qui la présente qu'elle ne vaut plus,
 * et rien de plus.
 *
 * La mise à jour de `last_used_at` est faite « au mieux » : elle n'est ni
 * transactionnelle ni attendue par l'appelant, et un appel concurrent qui
 * l'écrase ne fait perdre qu'une précision d'affichage.
 */
export async function resolveApiKey(
  handle: DatabaseHandle,
  token: string,
  now: Date = new Date(),
): Promise<ResolvedApiKey | null> {
  if (!isApiKeyToken(token)) return null;

  const rows = await handle.db
    .select({
      id: schema.mcpKey.id,
      userId: schema.mcpKey.userId,
      lastUsedAt: schema.mcpKey.lastUsedAt,
      revokedAt: schema.mcpKey.revokedAt,
    })
    .from(schema.mcpKey)
    .where(eq(schema.mcpKey.tokenHash, hashApiKeyToken(token)))
    .limit(1);

  const key = rows[0];
  if (key === undefined || key.revokedAt !== null) return null;

  const stale =
    key.lastUsedAt === null || now.getTime() - key.lastUsedAt.getTime() > LAST_USE_INTERVAL_MS;
  if (stale) {
    await handle.db
      .update(schema.mcpKey)
      .set({ lastUsedAt: now })
      .where(sql`${schema.mcpKey.id} = ${key.id} and ${schema.mcpKey.revokedAt} is null`);
  }

  return { keyId: key.id, userId: key.userId };
}

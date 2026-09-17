import { isApiKeyToken, MUE_SCOPES, resolveApiKey, type MueScope } from "@mue/auth";
import type { DatabaseHandle } from "@mue/db";
import type { AgentIdentity } from "./identity";

/**
 * Le garde des clés d'API sur `/mcp` : `Authorization: Bearer mue_…` devient une
 * identité d'agent, sans qu'aucune requête ne parte vers Better Auth.
 *
 * ## Pourquoi ce module existe à côté de `requireMcpAuth`
 *
 * `requireMcpAuth` est le garde OAuth : il vérifie une signature contre le JWKS,
 * lit les *claims*, et refuse en publiant le challenge RFC 9728 qui fait démarrer
 * une autorisation. Une clé n'a rien de tout cela — elle n'est pas signée, elle
 * n'expire pas, elle n'a pas de claims, et un client qui la présente ne doit
 * surtout pas être renvoyé vers une page de consentement. Les deux chemins
 * partagent le même endpoint et rien d'autre.
 *
 * Les trois issues sont donc distinguées explicitement, et c'est la raison
 * d'être du type {@link ApiKeyAuth} :
 *
 *  - `not-a-key` — l'en-tête n'est pas une clé de Mue (absent, un jeton de
 *    session Android, un JWT d'agent). L'appelant continue vers l'OAuth, et
 *    c'est ce qui garde les deux chemins vivants pendant la transition ;
 *  - `identity` — la clé est connue, vivante, et son propriétaire est le compte ;
 *  - `refused` — la chaîne **ressemble** à une clé de Mue et ne résout pas. Il
 *    n'y a pas de repli : une clé révoquée qui retomberait sur l'OAuth
 *    rouvrirait exactement la porte que la révocation vient de fermer.
 *
 * Le préfixe `mue_` est ce qui rend cette distinction possible sans toucher la
 * base : `isApiKeyToken` est une comparaison de chaîne, donc un jeton d'une autre
 * nature ne provoque jamais de `SELECT`.
 *
 * ## Les portées, et pourquoi il n'y en a pas
 *
 * Une clé vaut le jeu complet (`MUE_SCOPES`), décision du propriétaire pour un
 * serveur à un seul compte — la table `mcp_key` porte le raisonnement. Ce que
 * cela ne change pas : `buildMcpServer` filtre toujours le catalogue par portées
 * et revérifie à l'appel (`isToolPermitted`), donc le jour où un trousseau plus
 * fin revient, il n'y a rien à réécrire ici — seulement une identité à construire
 * avec d'autres portées.
 *
 * ## La révocation est la résolution
 *
 * Aucun appel à `isAgentRevoked` sur ce chemin, et ce n'est pas un oubli : cette
 * fonction interroge `oauthClient` et `oauthAccessToken`, deux tables qui ne
 * connaissent pas les clés. La vérification ici est la résolution elle-même —
 * `resolveApiKey` refuse une ligne dont `revoked_at` est posé — et elle est donc
 * aussi immédiate que la ligne qui la porte.
 */

/** Le schéma d'authentification attendu, tel qu'un client MCP l'écrit. */
const BEARER_PATTERN = /^Bearer[ ]+(\S+)$/i;

/**
 * Le jeton d'un en-tête `Authorization`, ou `null`.
 *
 * Le motif est ancré et exige un seul jeton : `Bearer a b` est refusé plutôt que
 * tronqué, parce qu'un en-tête malformé qui passe est un en-tête qu'on ne voit
 * jamais échouer. Le nom du schéma est comparé sans tenir compte de la casse,
 * comme la RFC 9110 le demande.
 */
export function readBearerToken(authorization: string | null): string | null {
  if (authorization === null) return null;
  const match = BEARER_PATTERN.exec(authorization.trim());
  if (match === null) return null;
  return match[1] ?? null;
}

/** Ce que le garde a compris de l'en-tête. Voir le commentaire d'en-tête du module. */
export type ApiKeyAuth =
  | { readonly kind: "not-a-key" }
  | { readonly kind: "identity"; readonly identity: AgentIdentity }
  | { readonly kind: "refused" };

/**
 * Toutes les portées, allouées une fois. Un `Set` par requête serait une
 * allocation par appel d'outil pour un contenu qui ne varie pas, et la valeur est
 * immuable (`ReadonlySet`) précisément pour pouvoir être partagée.
 */
const ALL_SCOPES: ReadonlySet<MueScope> = new Set(MUE_SCOPES);

/**
 * L'identité qu'une clé donne, ou l'absence de clé, ou un refus.
 *
 * `clientId` est l'identifiant de la clé, et ce n'est pas un détail de
 * présentation : les outils écrivent `originId: identity.clientId` dans le
 * journal, donc une écriture faite avec une clé est signée par cette clé dans
 * `sync_journal` et dans `agent_audit`. C'est ce qui rend une révocation
 * exploitable après coup — on sait ce que chaque clé a écrit.
 *
 * `tokenId` est `null` : il n'y a pas de jeton à révoquer séparément, la clé
 * *est* l'identité. Rien en aval ne lit ce champ autrement que pour la
 * vérification OAuth, qui n'a pas lieu ici.
 */
export async function authenticateApiKey(
  database: DatabaseHandle,
  authorization: string | null,
): Promise<ApiKeyAuth> {
  const token = readBearerToken(authorization);
  if (token === null || !isApiKeyToken(token)) return { kind: "not-a-key" };

  const resolved = await resolveApiKey(database, token);
  if (resolved === null) return { kind: "refused" };

  return {
    kind: "identity",
    identity: {
      userId: resolved.userId,
      clientId: resolved.keyId,
      scopes: ALL_SCOPES,
      tokenId: null,
    },
  };
}

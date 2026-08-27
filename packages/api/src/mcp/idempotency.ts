import { createHash } from "node:crypto";

/**
 * An agent's idempotency key, turned into the `mutationId` the sync contract requires.
 *
 * ## The bug this closes
 *
 * `mue.create_activity` declares `idempotencyKey` as `z.uuid()` — any version — and used it
 * *verbatim* as the mutation id. That worked for exactly as long as the write path did not
 * validate one: `provisional-activity-write.ts` called `recordMutation` directly and never
 * looked. The moment `create_activity` started going through `submitMutation`, like every other
 * write, `mutationIdSchema` applied — and it is `z.uuidv7()`. `crypto.randomUUID()` produces a
 * v4, which is what every SDK and every agent will reach for, so the first replay-safe call an
 * agent made was refused before its payload was read.
 *
 * That is the same failure as `SyncOutbox` minting a v4 where the schema said v7, in a second
 * place, found the same way: by running a real value through the whole path rather than by
 * comparing shapes.
 *
 * ## Why the key is not simply required to be a v7
 *
 * Because no agent can produce one. `crypto.randomUUID()` is v4 in every runtime that has it, and
 * `Bun.randomUUIDv7()` is not something an MCP client has. Requiring v7 would make section 14.6's
 * *"un outil d'écriture fournit une clé d'idempotence ou réutilise l'identifiant de mutation MCP
 * du client"* impossible to satisfy with the tools an agent actually holds — the key would be
 * offered, refused, and every retry would create a second session.
 *
 * ## Why the derivation is a hash and not a fresh v7
 *
 * The whole value of the key is that it is *stable across calls*: two calls carrying it must
 * produce the same mutation id, or FR-SYNC-006 has nothing to deduplicate against. So the id is a
 * pure function of the key. SHA-256 gives 256 bits to draw the 122 free bits from, and the
 * version and variant nibbles are then written where RFC 9562 puts them.
 *
 * The consequence is stated rather than hidden: **the timestamp field of the result is not a
 * timestamp**. A v7's leading 48 bits normally sort identifiers by creation time, which is what
 * makes a phone's outbox drain in the order it was written. An agent has no outbox — the mutation
 * is minted and applied by the same call — so there is no queue for the ordering to matter to,
 * and `mutationIdSchema` is a regular expression rather than a decoder. Nothing in this system
 * reads the timestamp back out of a mutation id; `sync_journal.sequence` is the only order there
 * is (section 12.3), and the server assigns it.
 *
 * The namespace makes the derivation specific to this use, so an agent that reuses one key across
 * two different tools gets two different mutation ids rather than having the second silently
 * replayed as the first.
 */
const NAMESPACE = "mue.mcp.idempotency.v1";

export function mutationIdFromIdempotencyKey(key: string): string {
  const digest = createHash("sha256").update(`${NAMESPACE}:${key}`).digest();

  const bytes = Uint8Array.prototype.slice.call(digest, 0, 16);
  // Version 7 in the high nibble of byte 6, per RFC 9562 section 5.7.
  bytes[6] = ((bytes[6] as number) & 0x0f) | 0x70;
  // Variant `10` in the top two bits of byte 8.
  bytes[8] = ((bytes[8] as number) & 0x3f) | 0x80;

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join("-");
}

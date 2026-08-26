// Name-based (version 5) UUIDs for the catalogue rows.
//
// `ExerciseCatalogSeed.kt` writes its identifiers down rather than generating them,
// for one reason: an id computed on the device is a different id on every device, and
// a synchronised aggregate whose primary key differs per install cannot be reconciled.
// The Ciqual catalogue is the same argument at five hundred times the scale, where
// writing five hundred UUIDs by hand is not an option — so the generator computes them
// once, deterministically, and ships them inside the asset.
//
// Determinism here means: the same `alim_code` yields the same UUID on every machine,
// in every regeneration, forever. RFC 4122 §4.3 gives exactly that.

import { createHash } from "node:crypto";

/**
 * The Mue Ciqual namespace, itself a version-5 UUID so that it is checkable rather
 * than magic: it is `uuidv5("mue.kristenjestin.fr/food/ciqual", DNS_NAMESPACE)`, and
 * `uuid.test.ts` re-derives it.
 */
export const CIQUAL_NAMESPACE = "7add067b-8471-5df7-aa5c-49adab77fb69";

/** RFC 4122 appendix C. */
export const DNS_NAMESPACE = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

function namespaceBytes(namespace: string): Buffer {
  const hex = namespace.replace(/-/g, "");
  if (!/^[0-9a-f]{32}$/i.test(hex)) throw new Error(`Not a UUID: ${namespace}`);
  return Buffer.from(hex, "hex");
}

/** RFC 4122 §4.3: SHA-1 of namespace ‖ name, with the version and variant bits set. */
export function uuidV5(name: string, namespace: string): string {
  const digest = createHash("sha1")
    .update(Buffer.concat([namespaceBytes(namespace), Buffer.from(name, "utf8")]))
    .digest();
  const bytes = Buffer.from(digest.subarray(0, 16));
  bytes[6] = ((bytes[6] as number) & 0x0f) | 0x50;
  bytes[8] = ((bytes[8] as number) & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join("-");
}

/**
 * The identifier of a Ciqual row.
 *
 * The name is `ciqual:<alim_code>` and not the food's label: a label is retranslated
 * between releases, a code is not, and an identifier that moved when a name was
 * corrected would orphan every journal line that referenced it.
 */
export function ciqualEntryId(alimCode: string): string {
  return uuidV5(`ciqual:${alimCode}`, CIQUAL_NAMESPACE);
}

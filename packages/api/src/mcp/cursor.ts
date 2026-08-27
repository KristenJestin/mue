import { z } from "zod";

/**
 * The pagination cursor of the MCP list tools.
 *
 * Deliberately *not* the sync cursor of `@mue/contracts`. That one wraps a journal
 * sequence and answers "what changed after position N" (section 12.3); this one
 * wraps the last key of a page and answers "what comes after this row". Reusing
 * the sync cursor here would let an agent hand a tool cursor to `/api/v1/sync` and
 * silently get a coherent-looking, wrong answer.
 *
 * Opaque and versioned for the same reasons as the sync cursor: opaque so no
 * client does arithmetic on the key, versioned so the shape can change without a
 * new tool. Keyset, not offset, so section 14.2's unbounded walk stays correct
 * when a row is inserted between two pages.
 */
const listCursorPayloadSchema = z.object({
  v: z.literal(1),
  /** The last key of the page just returned. Its meaning is the tool's own. */
  k: z.string().min(1).max(128),
});

export const listCursorSchema = z
  .string()
  .min(1)
  .max(512)
  .regex(/^[A-Za-z0-9_-]+$/, "expected an unpadded base64url cursor");

export class InvalidCursorError extends Error {}

export function encodeListCursor(key: string): string {
  const json = JSON.stringify(listCursorPayloadSchema.parse({ v: 1, k: key }));
  return btoa(json).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

export function decodeListCursor(cursor: string): string {
  try {
    const base64 = cursor.replaceAll("-", "+").replaceAll("_", "/");
    return listCursorPayloadSchema.parse(JSON.parse(atob(base64))).k;
  } catch {
    // The reason is dropped on purpose: a parser's message would describe the
    // encoding, and section 14.1 keeps internals off the MCP endpoint.
    throw new InvalidCursorError("the cursor is not one this server issued");
  }
}

/**
 * A two-part keyset, written as one opaque string.
 *
 * A list whose order is `(day desc, id)` or `(name, id)` needs both halves in its cursor:
 * a cursor on the first alone drops every other row of the group a page happened to end
 * inside, and one on the second alone cannot resume an ordering it does not follow.
 *
 * The separator is the last space, which is unambiguous for the pairs that exist: the
 * second half is always a UUID and a UUID contains no space, while the first half may
 * (`bench press`). Choosing the *last* space rather than the first is what makes a name
 * with a space in it survive the round trip.
 */
export function encodePairKey(first: string, second: string): string {
  return `${first} ${second}`;
}

export function decodePairKey(key: string): { first: string; second: string } {
  const separator = key.lastIndexOf(" ");
  return separator === -1
    ? { first: key, second: "" }
    : { first: key.slice(0, separator), second: key.slice(separator + 1) };
}

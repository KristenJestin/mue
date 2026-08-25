import { z } from "zod";

/**
 * Every 64-bit counter crosses the wire as a decimal string. JSON numbers give
 * Kotlin no precision guarantee, and PLATFORM-CONTRACT section 2 calls the value opaque.
 */
const decimalString = z.string().regex(/^\d+$/, "expected a decimal string");

/**
 * Inner payload of the sync cursor, base64url-wrapped before it leaves the server.
 * Versioned so its shape can change without an API version bump.
 */
export const cursorPayloadSchema = z.object({
  v: z.literal(1),
  seq: decimalString,
});

export type CursorPayload = z.infer<typeof cursorPayloadSchema>;

/**
 * Per-aggregate optimistic-concurrency counter, deliberately distinct from the
 * per-user journal sequence: conflating the two loses changes.
 */
export const revisionSchema = decimalString;

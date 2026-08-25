import { z } from "zod";
import { sequenceSchema } from "./primitives";

/**
 * Inner payload of the sync cursor, base64url-wrapped before it leaves the server.
 *
 * Versioned so the cursor's shape can change without an API version bump: a client
 * that round-trips the opaque string never sees `v` change under it.
 */
export const cursorPayloadSchema = z
  .object({
    v: z.literal(1),
    seq: sequenceSchema,
  })
  .meta({
    id: "CursorPayload",
    description: "Decoded cursor. Server-side only: clients receive and return the encoded string.",
  });

export type CursorPayload = z.infer<typeof cursorPayloadSchema>;

/**
 * The wire form: unpadded base64url of the payload above.
 *
 * Opaque on purpose. A client that can read the sequence will eventually add one to it,
 * and PRD section 12.3 makes the journal position the server's to assign.
 */
export const cursorSchema = z
  .string()
  .min(1)
  .max(512)
  .regex(/^[A-Za-z0-9_-]+$/, "expected an unpadded base64url cursor")
  .meta({
    id: "Cursor",
    description: "Opaque pagination cursor. Store and return it verbatim; never parse it.",
    examples: ["eyJ2IjoxLCJzZXEiOiI0MiJ9"],
  });

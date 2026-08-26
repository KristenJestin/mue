import { type CursorPayload, cursorPayloadSchema } from "@mue/contracts";

const BASE64URL_FROM = ["+", "/"] as const;
const BASE64URL_TO = ["-", "_"] as const;

/**
 * Cursor encoding is a business rule, not a schema concern, so it lives here:
 * Hono routes, server functions and MCP tools all call this one implementation.
 * The wire form is opaque precisely so no client does arithmetic on the sequence.
 */
export function encodeCursor(payload: CursorPayload): string {
  const json = JSON.stringify(cursorPayloadSchema.parse(payload));
  return btoa(json)
    .replaceAll(BASE64URL_FROM[0], BASE64URL_TO[0])
    .replaceAll(BASE64URL_FROM[1], BASE64URL_TO[1])
    .replaceAll("=", "");
}

export function decodeCursor(cursor: string): CursorPayload {
  const base64 = cursor
    .replaceAll(BASE64URL_TO[0], BASE64URL_FROM[0])
    .replaceAll(BASE64URL_TO[1], BASE64URL_FROM[1]);
  return cursorPayloadSchema.parse(JSON.parse(atob(base64)));
}

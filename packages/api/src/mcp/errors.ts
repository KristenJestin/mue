import { type MueError, mueErrorSchema } from "@mue/contracts";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

/**
 * `MueError` as an MCP tool sees it.
 *
 * The wire schema in `@mue/contracts` is the source of truth and is reused verbatim;
 * this is only the shape a tool's `outputSchema` advertises, so an agent reading the
 * tool catalogue learns the error vocabulary before it makes its first mistake.
 */
export const mcpErrorSchema = mueErrorSchema;

/**
 * Every Mue tool answers with the same three-field envelope.
 *
 * The reason is a real constraint, not symmetry for its own sake: the SDK client
 * validates `structuredContent` against the tool's `outputSchema` whenever it is
 * present -- including on an error result. An error-shaped object next to a
 * success-shaped schema would therefore fail validation inside the client and the
 * agent would see a protocol error instead of section 14.4's actionable business
 * error. One envelope that describes both outcomes is what makes the business
 * error survive the trip.
 */
export function envelopeSchema<T extends z.ZodType>(data: T) {
  return z.object({
    status: z
      .enum(["ok", "error"])
      .describe("`ok` when `data` is present, `error` when `error` is."),
    data: data.nullable().describe("The result, or null when the call failed."),
    error: mcpErrorSchema
      .nullable()
      .describe("A structured, actionable business error, or null when the call succeeded."),
  });
}

export function toolSuccess<T>(data: T): CallToolResult {
  const structuredContent = { status: "ok" as const, data, error: null };
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent) }],
    structuredContent,
  };
}

/**
 * `isError` is set as well as `status: "error"`. The flag is what a client shows a
 * human and what stops the SDK validating the envelope twice; the field is what an
 * agent branches on. Both, because a client that reads only one of them exists.
 */
export function toolFailure(error: MueError): CallToolResult {
  const structuredContent = { status: "error" as const, data: null, error };
  return {
    content: [{ type: "text", text: JSON.stringify(structuredContent) }],
    structuredContent,
    isError: true,
  };
}

export function missingRequiredField(field: string, message: string): MueError {
  return { code: "sync.missing_required_field", message, retryable: false, field };
}

export function invalidPayload(message: string, field?: string): MueError {
  return {
    code: "sync.invalid_payload",
    message,
    retryable: false,
    ...(field === undefined ? {} : { field }),
  };
}

export function forbidden(message: string): MueError {
  return { code: "auth.forbidden", message, retryable: false };
}

export function unauthenticated(message: string): MueError {
  return { code: "auth.unauthenticated", message, retryable: false };
}

/**
 * The only mapping an unexpected failure gets.
 *
 * Sections 14.1 and 16 forbid the MCP endpoint from exposing tables, SQL, the file
 * system or the process. A driver's own message names all four -- a constraint
 * name, a column, a connection string, a stack path -- so nothing from `cause`
 * reaches the agent. It is re-thrown to the caller's logger instead, where section
 * 16 already governs what may be written down.
 */
export function internalError(): MueError {
  return {
    code: "server.internal",
    message: "The server could not complete the request. Retrying later may work.",
    retryable: true,
  };
}

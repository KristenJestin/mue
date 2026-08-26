import type { MueScope } from "@mue/auth";
import type { CallToolResult, ToolAnnotations } from "@modelcontextprotocol/sdk/types.js";
import type { z } from "zod";
import type { AgentIdentity } from "../identity";
import type { MueMcpServices } from "../services";

export interface ToolContext {
  readonly identity: AgentIdentity;
  readonly services: MueMcpServices;
}

/**
 * One Mue tool, everything about it in a single object.
 *
 * `scopes` sits next to the handler rather than in a table elsewhere on purpose:
 * section 22.5 requires that a read scope reach no write tool, and a permission that
 * lives in the same object as the code it guards cannot be forgotten when a tool is
 * added.
 *
 * `handler` takes an untyped record because the registry holds tools with different
 * input shapes and a generic parameter would make the list unassignable to itself.
 * The cast each tool makes back to its own argument type is safe for a precise
 * reason: the SDK parses `arguments` against `inputSchema` and returns
 * `InvalidParams` before the handler is ever called, so a handler only ever sees a
 * value the schema accepted.
 */
export interface MueTool {
  readonly name: string;
  readonly title: string;
  readonly description: string;
  readonly inputSchema: z.ZodRawShape;
  readonly outputSchema: z.ZodRawShape;
  /**
   * The four standard MCP annotations of section 14.1. All four are always set, even
   * where the specification calls one meaningless -- `destructiveHint` is only
   * consulted when `readOnlyHint` is false -- because the section asks that a client
   * be able to reason about what a tool does, and an absent hint is not an answer.
   */
  readonly annotations: Required<
    Pick<ToolAnnotations, "readOnlyHint" | "destructiveHint" | "idempotentHint" | "openWorldHint">
  >;
  /** Every scope the caller must hold. Empty means the tool needs none. */
  readonly scopes: readonly MueScope[];
  readonly handler: (
    context: ToolContext,
    args: Record<string, unknown>,
  ) => Promise<CallToolResult>;
}

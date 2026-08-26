import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { forbidden, internalError, toolFailure } from "./errors";
import type { AgentIdentity } from "./identity";
import { MUE_MCP_INSTRUCTIONS, MUE_MCP_SERVER_INFO } from "./protocol";
import type { MueMcpServices } from "./services";
import { isToolPermitted, MUE_TOOLS } from "./tools";

export interface BuildMcpServerOptions {
  readonly identity: AgentIdentity;
  readonly services: MueMcpServices;
  /** Where an unexpected failure goes. Section 16 governs what may be written down. */
  readonly onInternalError?: (toolName: string, error: unknown) => void;
}

/**
 * One `McpServer` per request.
 *
 * The transport is stateless, so building the server here rather than once at start-up
 * costs a few object allocations and buys the thing that matters: the catalogue an
 * agent sees is the catalogue its own token authorises. A shared server would have to
 * advertise the union of every scope and refuse at call time, which section 22.5
 * tolerates but which makes every agent discover tools it can never use.
 */
export function buildMcpServer(options: BuildMcpServerOptions): McpServer {
  const { identity, services } = options;

  const server = new McpServer(MUE_MCP_SERVER_INFO, {
    instructions: MUE_MCP_INSTRUCTIONS,
    capabilities: { tools: {} },
  });

  for (const tool of MUE_TOOLS) {
    if (!isToolPermitted(tool, identity.scopes)) continue;

    server.registerTool(
      tool.name,
      {
        title: tool.title,
        description: tool.description,
        inputSchema: tool.inputSchema,
        outputSchema: tool.outputSchema,
        annotations: { title: tool.title, ...tool.annotations },
      },
      async (args: unknown) => {
        // Checked again on the way in. The filter above shapes what is advertised; a
        // client may still call a name it remembers from a wider authorization, and
        // section 22.5 is about what the server does, not about what it offered.
        if (!isToolPermitted(tool, identity.scopes)) {
          const missing = tool.scopes.filter((scope) => !identity.scopes.has(scope));
          return toolFailure(
            forbidden(
              `This tool needs the ${missing.join(" and ")} scope. Ask for it and authorize again.`,
            ),
          );
        }

        try {
          return await tool.handler(
            { identity, services },
            (args ?? {}) as Record<string, unknown>,
          );
        } catch (error) {
          // Nothing from the failure reaches the agent: a driver message names a
          // table, a column or a connection string, and sections 14.1 and 16 keep all
          // three off this endpoint.
          options.onInternalError?.(tool.name, error);
          return toolFailure(internalError());
        }
      },
    );
  }

  return server;
}

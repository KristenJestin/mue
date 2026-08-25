import type { CimdOptions } from "@better-auth/cimd";
import type { McpOptions } from "@better-auth/mcp";
import type { BetterAuthOptions } from "better-auth";

/** Better Auth is the identity authority for all three shapes: Web cookie, Android bearer, MCP OAuth. */
export type MueAuthOptions = BetterAuthOptions;

/** Supplies protected-resource metadata and the OAuth 2.1 + PKCE flow for agents. */
export type MueMcpOptions = McpOptions;

/**
 * The Client ID Metadata Document profile the agent OAuth link rests on.
 *
 * `fetchClientMetadataResource` is a required option and is deliberately not
 * defaulted here: the transport must resolve the hostname exactly once, reject
 * RFC 6890 special-use addresses, pin the resolved address and refuse redirects.
 * That SSRF boundary is the application's to build at its runtime.
 */
export type MueCimdOptions = CimdOptions;

export { validateClientIdUrl } from "@better-auth/cimd";

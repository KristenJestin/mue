/**
 * The Mue MCP server: PRD sections 8.3, 14, 15 and 16.
 *
 * Streamable HTTP on `/mcp`, OAuth 2.1 + PKCE through `@better-auth/mcp` and
 * `@better-auth/cimd`, business tools that call `@mue/domain` and never the tables.
 */

export {
  ACTIVITY_ENVIRONMENTS,
  ACTIVITY_MOVEMENTS,
  AGENT_ACTIVITY_SOURCE,
  activitySessionPayloadSchema,
  activitySessionViewSchema,
  type ActivitySessionPayload,
  type ActivitySessionView,
} from "./activity";
export {
  decodeListCursor,
  decodePairKey,
  encodeListCursor,
  encodePairKey,
  InvalidCursorError,
} from "./cursor";
export {
  createAgentMutationService,
  createActivitySessionService,
  isUsingProvisionalActivityWrite,
  type AgentMutationService,
} from "./domain-bridge";
export { envelopeSchema, toolFailure, toolSuccess } from "./errors";
export { IdentityError, isAgentRevoked, readAgentIdentity, type AgentIdentity } from "./identity";
export {
  MUE_MCP_INSTRUCTIONS,
  MUE_MCP_PROTOCOL_VERSION,
  MUE_MCP_SERVER_INFO,
  PRD_REQUESTED_PROTOCOL_VERSION,
} from "./protocol";
export { createMcpApp, createOAuthDiscoveryApp, MCP_PATH, type McpRouteOptions } from "./route";
export { buildMcpServer, type BuildMcpServerOptions } from "./server";
export type {
  ActivityStatistics,
  AgentAuditEntry,
  AgentMutationCommand,
  AgentMutationResult,
  AggregateMetadata,
  CreateActivityCommand,
  CreateActivityResult,
  ListActivitiesQuery,
  ListActivitiesResult,
  ListCustomExercisesQuery,
  ListCustomExercisesResult,
  ListWeightMeasurementsQuery,
  ListWeightMeasurementsResult,
  MueMcpServices,
  StoredAggregate,
  SyncStatus,
  WeightMeasurementView,
  WeightStatistics,
} from "./services";
export { createMueMcpServices } from "./store";
export {
  createActivityTool,
  isToolPermitted,
  listWeightMeasurementsTool,
  MUE_TOOLS,
  toolsForScopes,
  type MueTool,
  type ToolContext,
} from "./tools";

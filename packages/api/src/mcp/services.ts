import type { MueError, OriginType } from "@mue/contracts";
import type { ActivitySessionPayload, ActivitySessionView } from "./activity";

/**
 * Everything the MCP tools are allowed to reach.
 *
 * PRD section 20.2 requires the Hono routes, the TanStack Start server functions and
 * the MCP tools to call the *same* services, so a tool never talks to Drizzle and never
 * holds a rule of its own. This interface is that seam: the tools depend on it, and
 * `./store.ts` is the one implementation, which in turn delegates every write to
 * `@mue/domain` through `./domain-bridge.ts`.
 *
 * It is also what makes the tools testable without a database, and what makes the
 * "never expose tables, raw SQL, the filesystem or the process" rule of sections 14.1
 * and 16 structural: there is no method here through which a query could be passed.
 */

export interface WeightMeasurementView {
  readonly date: string;
  /** Hundredths of a kilogram, the integer unit Android stores. */
  readonly weightCg: number;
  readonly revision: string;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly deletedAt: string | null;
  readonly originType: OriginType;
  readonly originId: string | null;
  readonly lastMutationId: string;
}

export interface ListWeightMeasurementsQuery {
  readonly userId: string;
  /** Inclusive lower bound, or null. Null on both ends means the whole history. */
  readonly from: string | null;
  /** Inclusive upper bound, or null. */
  readonly to: string | null;
  /** Keyset: the date the previous page ended on. Null starts at the beginning. */
  readonly afterDate: string | null;
  readonly limit: number;
  readonly includeDeleted: boolean;
}

export interface ListWeightMeasurementsResult {
  readonly measurements: readonly WeightMeasurementView[];
  readonly hasMore: boolean;
}

export interface CreateActivityCommand {
  readonly userId: string;
  /** FR-SYNC-006 and section 14.6: replaying this id repeats no effect. */
  readonly mutationId: string;
  readonly originId: string;
  readonly payload: ActivitySessionPayload;
  /** The agent's own clock, for display and audit only (section 12.3). */
  readonly clientOccurredAt: string;
}

export interface CreateActivityResult {
  readonly activity: ActivitySessionView;
  /** False when the mutation id had already been applied and this is the stored result. */
  readonly created: boolean;
}

/**
 * Section 14.7, verbatim: agent identity, tool name, server instant, mutation id,
 * aggregates touched, result, revision created, and any error.
 *
 * There is no field for a prompt or a conversation, and that is the point -- the
 * section says they are not wanted, and a shape that cannot hold them cannot grow
 * one by accident.
 */
export interface AgentAuditEntry {
  readonly agentId: string;
  readonly toolName: string;
  readonly mutationId: string | null;
  readonly aggregates: readonly { readonly type: string; readonly id: string }[];
  readonly result: "ok" | "error";
  readonly revision: string | null;
  readonly error: MueError | null;
}

export interface MueMcpServices {
  listWeightMeasurements(query: ListWeightMeasurementsQuery): Promise<ListWeightMeasurementsResult>;

  /**
   * FR-SYNC-008: the last moment the server saw the Android phone synchronise, so no
   * agent infers a freshness guarantee the server cannot give. Null when it never has.
   */
  lastAndroidSyncAt(userId: string): Promise<string | null>;

  createActivitySession(command: CreateActivityCommand): Promise<CreateActivityResult>;

  recordAudit(entry: AgentAuditEntry): Promise<void>;
}

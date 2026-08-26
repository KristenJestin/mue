import type { OriginType } from "@mue/contracts";
import type { DatabaseHandle } from "@mue/db";
import { schema } from "@mue/db";
import { and, desc, eq, gte, isNull, lt, lte, max } from "drizzle-orm";
import { createActivitySessionService } from "./domain-bridge";
import type {
  AgentAuditEntry,
  CreateActivityCommand,
  CreateActivityResult,
  ListWeightMeasurementsQuery,
  ListWeightMeasurementsResult,
  MueMcpServices,
  WeightMeasurementView,
} from "./services";

/** `origin_type` is a text column; anything unrecognised is reported as `server`. */
function asOriginType(value: string): OriginType {
  return value === "android" || value === "agent" ? value : "server";
}

export function createMueMcpServices(database: DatabaseHandle): MueMcpServices {
  const createActivity = createActivitySessionService();

  return {
    async listWeightMeasurements(
      query: ListWeightMeasurementsQuery,
    ): Promise<ListWeightMeasurementsResult> {
      const { measurements } = schema;

      const filters = [eq(measurements.userId, query.userId)];
      if (query.from !== null) filters.push(gte(measurements.date, query.from));
      if (query.to !== null) filters.push(lte(measurements.date, query.to));
      if (!query.includeDeleted) filters.push(isNull(measurements.deletedAt));
      // Keyset, not offset: the page after a date is well defined even when rows are
      // inserted between two calls, which is what makes section 14.2's unbounded walk
      // terminate without repeating or skipping a day.
      if (query.afterDate !== null) filters.push(lt(measurements.date, query.afterDate));

      const rows = await database.db
        .select()
        .from(measurements)
        .where(and(...filters))
        .orderBy(desc(measurements.date))
        // One extra row answers `hasMore` without a second count query.
        .limit(query.limit + 1);

      const page = rows.slice(0, query.limit);
      return {
        measurements: page.map((row): WeightMeasurementView => ({
          date: row.date,
          weightCg: row.weightCg,
          revision: row.revision.toString(),
          createdAt: row.createdAt.toISOString(),
          updatedAt: row.updatedAt.toISOString(),
          deletedAt: row.deletedAt?.toISOString() ?? null,
          originType: asOriginType(row.originType),
          originId: row.originId,
          lastMutationId: row.lastMutationId,
        })),
        hasMore: rows.length > query.limit,
      };
    },

    /**
     * FR-SYNC-008, from the only evidence the server holds today: the most recent
     * change the phone itself authored. It is a lower bound on the phone's freshness,
     * and erring low is the safe direction -- an agent is told the data may be older
     * than it is, never newer.
     *
     * TODO(sync-agent): a pull leaves no trace, so a phone that only receives never
     * moves this instant. When the sync engine records per-device state, read the last
     * successful exchange instead and this becomes exact.
     */
    async lastAndroidSyncAt(userId: string): Promise<string | null> {
      const rows = await database.db
        .select({ at: max(schema.syncJournal.recordedAt) })
        .from(schema.syncJournal)
        .where(
          and(eq(schema.syncJournal.userId, userId), eq(schema.syncJournal.originType, "android")),
        );
      const at = rows[0]?.at;
      return at === null || at === undefined ? null : new Date(at).toISOString();
    },

    createActivitySession(command: CreateActivityCommand): Promise<CreateActivityResult> {
      return createActivity(database, command);
    },

    /**
     * Section 14.7's eight fields. `occurred_at` is the column default, so the server
     * instant is the database's own clock and not one an agent could influence.
     */
    async recordAudit(entry: AgentAuditEntry): Promise<void> {
      await database.db.insert(schema.agentAudit).values({
        id: crypto.randomUUID(),
        agentId: entry.agentId,
        toolName: entry.toolName,
        mutationId: entry.mutationId,
        aggregates: entry.aggregates,
        result: entry.result,
        revision: entry.revision === null ? null : BigInt(entry.revision),
        error: entry.error,
      });
    },
  };
}

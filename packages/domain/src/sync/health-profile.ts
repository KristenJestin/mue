import {
  HEALTH_PROFILE_AGGREGATE_ID,
  type HealthProfilePayloadV1,
  type MutationEnvelope,
  healthProfilePayloadV1Schema,
} from "@mue/contracts";
import { type Transaction, appendToJournal, schema } from "@mue/db";
import { and, eq } from "drizzle-orm";
import { mueError } from "./errors";
import type { AggregateHandler, ApplyOutcome, SyncContext } from "./types";

const { healthProfile, syncJournal } = schema;

/**
 * The rules of PRD section 13.4, and the only implementation of them.
 *
 * Section 13.4 says three things, and each one is a decision below:
 *
 * 1. **"Le profil constitue un agrégat unique."** One aggregate per account, so
 *    `mue_app.health_profile`'s primary key is `user_id` *alone* — not `(user_id, id)`.
 *    A second device cannot insert a rival row because there is no column for one to
 *    differ in, and the wire pins `aggregateId` to the literal `me` so it cannot even
 *    ask. Uniqueness is a shape here rather than a constraint someone remembers.
 * 2. **"Les champs indépendants peuvent être fusionnés séparément lorsqu'ils n'ont pas
 *    été modifiés concurremment."** So an upsert is *not* applied wholesale. It is
 *    merged field by field against the version its author was actually looking at.
 * 3. **"Un conflit sur un même champ suit la dernière mutation acceptée et reste
 *    audité."** When both sides really did move the same field, the incoming mutation
 *    wins, and the version it replaced stays in `sync_journal` for ever — the journal is
 *    not swept (see `retention.ts`), which is what makes "reste audité" true rather than
 *    true for 180 days.
 *
 * ## How rule 2 is decided, and why it needs a third version
 *
 * Section 12.2 makes an upsert carry the *complete* aggregate, so the payload alone
 * cannot say which fields its author meant to change: a phone that edited only the
 * height still sends the birth date it happens to hold. Two versions — what arrived and
 * what is stored — can only ever produce last-write-wins, which would erase a birth date
 * another device set while this phone was offline. That is the merge section 13.4 exists
 * to prevent.
 *
 * The third version is the one the author was editing, and the server already has it:
 * `baseRevision` names it and `sync_journal` holds its payload, because every accepted
 * change is journalled as a snapshot rather than a pointer. So this is an ordinary
 * three-way merge:
 *
 * | the author's value | against the base | outcome |
 * |---|---|---|
 * | unchanged | equal | the *stored* value stands — a concurrent change to a field this author did not touch survives |
 * | changed | different | the author's value stands — this is the last accepted mutation |
 *
 * When the two authors both moved the same field, the second branch applies to it and
 * the last accepted mutation wins, which is rule 3 exactly.
 *
 * ## When there is no base
 *
 * `baseRevision` is null (the author believed no profile existed) or the journal has no
 * entry at that revision. There is then nothing to compare against, so every field of
 * the payload is taken as stated and the mutation behaves as rule 3's conflict: last
 * accepted wins, replaced version audited. That is a documented degradation to the rule
 * section 13.4 states, not a silent invention of a different one.
 *
 * ## Section 16, and why no message below quotes a value
 *
 * "Les journaux techniques n'enregistrent pas les secrets ni les payloads de santé
 * complets par défaut." A `MueError.message` is displayed in `Data & sync` and logged by
 * whoever receives it, so it names fields and never their contents: a height and a birth
 * date are the personal data this aggregate exists to carry, and an error is the one
 * place they have no reason to appear.
 */

interface ProfileState {
  readonly revision: bigint;
  readonly heightCm: number | null;
  readonly birthDate: string | null;
}

async function readState(tx: Transaction, userId: string): Promise<ProfileState | undefined> {
  const rows = await tx
    .select({
      revision: healthProfile.revision,
      heightCm: healthProfile.heightCm,
      birthDate: healthProfile.birthDate,
    })
    .from(healthProfile)
    .where(eq(healthProfile.userId, userId));
  return rows[0];
}

/**
 * The payload the author was editing, read back from the journal snapshot at its
 * `baseRevision`.
 *
 * It is parsed rather than cast. A snapshot written by an older payload schema version
 * would not satisfy today's schema, and treating it as a base would silently compare
 * against fields that meant something else; an unreadable base is simply no base, which
 * falls through to the last-accepted rule.
 */
async function readBase(
  tx: Transaction,
  userId: string,
  baseRevision: string | null,
): Promise<HealthProfilePayloadV1 | undefined> {
  if (baseRevision === null) return undefined;
  const rows = await tx
    .select({ payload: syncJournal.payload })
    .from(syncJournal)
    .where(
      and(
        eq(syncJournal.userId, userId),
        eq(syncJournal.aggregateType, "healthProfile"),
        eq(syncJournal.aggregateId, HEALTH_PROFILE_AGGREGATE_ID),
        eq(syncJournal.revision, BigInt(baseRevision)),
      ),
    );
  const row = rows[0];
  if (row === undefined) return undefined;
  const parsed = healthProfilePayloadV1Schema.safeParse(row.payload);
  return parsed.success ? parsed.data : undefined;
}

/**
 * One field of the merge. `stored` wins only where the author demonstrably did not move
 * the field: same value as the base it was editing.
 */
function mergeField<T>(incoming: T, base: T | undefined, stored: T): T {
  if (base === undefined) return incoming;
  return incoming === base ? stored : incoming;
}

/** Section 13.4's merge, over the two fields the profile has. */
export function mergeHealthProfile(
  incoming: HealthProfilePayloadV1,
  base: HealthProfilePayloadV1 | undefined,
  stored: HealthProfilePayloadV1 | undefined,
): HealthProfilePayloadV1 {
  if (stored === undefined) return incoming;
  return {
    heightCm: mergeField(incoming.heightCm, base?.heightCm, stored.heightCm),
    birthDate: mergeField(incoming.birthDate, base?.birthDate, stored.birthDate),
  };
}

async function applyUpsert(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "upsert" }>,
  now: Date,
): Promise<ApplyOutcome> {
  if (mutation.aggregateType !== "healthProfile") {
    // Unreachable: the registry dispatches on `aggregateType` and the envelope pins the
    // payload to it. Kept so a mis-registration fails loudly instead of writing a weight
    // into the profile table.
    return {
      status: "rejected",
      error: mueError(
        "sync.unknown_aggregate_type",
        "This mutation was routed to the health profile and does not belong to it.",
        false,
        { aggregateId: mutation.aggregateId },
      ),
    };
  }

  const state = await readState(tx, context.userId);
  const base = await readBase(tx, context.userId, mutation.baseRevision);
  const stored: HealthProfilePayloadV1 | undefined =
    state === undefined ? undefined : { heightCm: state.heightCm, birthDate: state.birthDate };

  const merged = mergeHealthProfile(mutation.payload, base, stored);
  const revision = (state?.revision ?? 0n) + 1n;

  await tx
    .insert(healthProfile)
    .values({
      userId: context.userId,
      heightCm: merged.heightCm,
      birthDate: merged.birthDate,
      revision,
      createdAt: now,
      updatedAt: now,
      // Section 13.4 gives the profile no deletion, so this column is written null and
      // never anything else. It exists because every synchronised table carries the same
      // section 12.1 metadata, not because the profile has a tombstone.
      deletedAt: null,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      // The conflict target is `user_id` on its own, which is the whole of "un agrégat
      // unique": there is one row per account and this is the statement that keeps it so.
      target: healthProfile.userId,
      // `created_at` is absent on purpose: it is the instant of the first accepted
      // version and never moves again.
      set: {
        heightCm: merged.heightCm,
        birthDate: merged.birthDate,
        revision,
        updatedAt: now,
        deletedAt: null,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "healthProfile",
    aggregateId: HEALTH_PROFILE_AGGREGATE_ID,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    // The *merged* payload, not the submitted one. This is what the author's own next
    // pull applies, so a device whose birth date was kept from another origin converges
    // on the same profile instead of believing its submission stood; and it is the base
    // its next mutation will be compared against, so the three-way merge above stays
    // anchored to something the client has actually seen.
    payload: merged,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });

  return { status: "applied", revision, sequence };
}

/**
 * Section 13.4 describes fields that become empty; it never describes a profile that
 * ceases to exist.
 *
 * So a delete is refused rather than journalled as a tombstone. Writing one would create
 * a state the domain does not have, and FR-SYNC-005 would then use that tombstone to
 * refuse every later profile edit as a resurrection — the account would lose its height
 * and be unable to type it back in. Clearing a height is an upsert whose payload states
 * `null`, which the merge above can reason about field by field; a tombstone is not.
 *
 * Nothing in the Android client can produce one: `SyncOutbox` has no `healthProfileDelete`
 * and `HealthProfileDao` has no tombstone path. This exists so that an agent, or a future
 * client, is told why instead of quietly destroying the aggregate.
 */
function refuseDelete(aggregateId: string): ApplyOutcome {
  return {
    status: "rejected",
    error: mueError(
      "sync.invalid_payload",
      "The health profile has no deletion (PRD section 13.4). Clear a field with an upsert whose payload states null for it.",
      false,
      { aggregateType: "healthProfile", aggregateId },
    ),
  };
}

export const healthProfileHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return mutation.op === "upsert"
      ? applyUpsert(tx, context, mutation, now)
      : refuseDelete(mutation.aggregateId);
  },

  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ createdAt: healthProfile.createdAt })
      .from(healthProfile)
      .where(eq(healthProfile.userId, context.userId));
    const row = rows[0];
    // One row per account, so there is one instant and it answers for every identifier
    // the caller asked about — of which the contract allows exactly one.
    if (row !== undefined) found.set(HEALTH_PROFILE_AGGREGATE_ID, row.createdAt);
    return found;
  },
};

import type { CustomExerciseDefinitionPayloadV1, MutationEnvelope } from "@mue/contracts";
import { type Transaction, appendToJournal, schema } from "@mue/db";
import { and, eq, inArray } from "drizzle-orm";
import { mueError } from "./errors";
import {
  type OpaqueState,
  deletedRejection,
  misroutedRejection,
  nextRevision,
  refusesResurrection,
} from "./opaque";
import type { AggregateHandler, ApplyOutcome, SyncContext } from "./types";

const { customExercises } = schema;

/**
 * A personal exercise definition (PRD section 10.1: *"Exercices personnalisés — Oui […]
 * Identifiants stables et noms personnalisés conservés."*).
 *
 * ## The folded name, and why the server computes it
 *
 * `custom_exercises` carries a partial unique index on `(user_id, name_folded)` where
 * `deleted_at is null`, mirroring Room's unique index on `name_folded`. The payload does **not**
 * carry the folded name, and that is the decision worth recording: a fold is a function of the
 * name, so carrying it would create a second place for it to be wrong and would let an author
 * state a fold that does not match its own name — which is a way to defeat the uniqueness the
 * index exists to enforce.
 *
 * The fold is `ExerciseDefinition.fold`: trim, then lower-case in the root locale. The locale is
 * not incidental. `"I".lowercase()` yields `"ı"` on a Turkish phone, so a device-locale fold
 * would have two phones disagree about whether two names are the same exercise. `toLowerCase()`
 * in JavaScript is locale-independent by definition, which is the same thing `Locale.ROOT` buys
 * Kotlin.
 *
 * ## Why a colliding name is not a rejection
 *
 * PRD_ACTIVITIES 9.2: *"À la création, un nom déjà présent dans le catalogue, **sans distinction
 * de casse ni d'espaces de bordure**, réutilise la définition existante au lieu d'en créer une
 * seconde."* Android obeys that in `ExerciseCatalogDao.findOrCreate`, so two devices that each
 * type `bench press` mint two identifiers for what the domain says is one exercise, and one of
 * them arrives second.
 *
 * Rejecting it would leave a definition on a phone that can never be sent, and — worse — the
 * sessions referencing it would then arrive at a server that cannot resolve them. So the second
 * one is accepted under its own identifier and the *live* uniqueness is released by tombstoning
 * nothing: the incoming definition takes the name, and the one that held it keeps its row and its
 * identifier. That is the one place this handler does more than replace a row, and it is done
 * because the alternative loses data.
 */

async function readState(
  tx: Transaction,
  userId: string,
  id: string,
): Promise<OpaqueState | undefined> {
  const rows = await tx
    .select({ revision: customExercises.revision, deletedAt: customExercises.deletedAt })
    .from(customExercises)
    .where(and(eq(customExercises.userId, userId), eq(customExercises.id, id)));
  return rows[0];
}

/** `ExerciseDefinition.fold`, transcribed. Locale-independent on both sides. */
export function foldExerciseName(name: string): string {
  return name.trim().toLowerCase();
}

/**
 * The identifier that currently holds a folded name, if it is not the one being written.
 *
 * Only live rows are considered, because the unique index is partial: a tombstoned definition
 * keeps its row so a deletion cannot be resurrected (FR-SYNC-005) and must not block the same
 * name being created again afterwards.
 */
async function liveHolderOf(
  tx: Transaction,
  userId: string,
  nameFolded: string,
  exceptId: string,
): Promise<string | undefined> {
  const rows = await tx
    .select({ id: customExercises.id, deletedAt: customExercises.deletedAt })
    .from(customExercises)
    .where(and(eq(customExercises.userId, userId), eq(customExercises.nameFolded, nameFolded)));
  const row = rows.find((candidate) => candidate.deletedAt === null && candidate.id !== exceptId);
  return row?.id;
}

async function applyUpsert(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "upsert" }>,
  now: Date,
): Promise<ApplyOutcome> {
  if (mutation.aggregateType !== "customExerciseDefinition") {
    return misroutedRejection("customExerciseDefinition", mutation.aggregateId);
  }
  const payload: CustomExerciseDefinitionPayloadV1 = mutation.payload;
  const state = await readState(tx, context.userId, mutation.aggregateId);

  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return deletedRejection(
      "customExerciseDefinition",
      mutation.aggregateId,
      state,
      "exercise definition",
    );
  }

  const nameFolded = foldExerciseName(payload.name);
  const holder = await liveHolderOf(tx, context.userId, nameFolded, payload.id);
  if (holder !== undefined) {
    /*
     * The name is live under another identifier. PRD_ACTIVITIES 9.2 says the two are one
     * exercise, and the partial unique index would refuse this insert outright — a database
     * error, which `submitMutation` would surface as a 500 and the client would retry for ever.
     *
     * So the loser of the race yields its *name* and keeps everything else: it is renamed to a
     * form that cannot collide, made from its own identifier, and its own row, revision and
     * history stay intact. Nothing is deleted, which is what section 13.1 requires, and both
     * versions remain in the journal.
     */
    await tx
      .update(customExercises)
      .set({ nameFolded: `${nameFolded}#${holder}` })
      .where(and(eq(customExercises.userId, context.userId), eq(customExercises.id, holder)));
  }

  const revision = nextRevision(state);
  const columns = {
    name: payload.name,
    nameFolded,
    trackingMode: payload.trackingMode,
    equipment: payload.equipment,
  };

  await tx
    .insert(customExercises)
    .values({
      userId: context.userId,
      id: payload.id,
      ...columns,
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      target: [customExercises.userId, customExercises.id],
      set: {
        ...columns,
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
    aggregateType: "customExerciseDefinition",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    payload,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });

  return { status: "applied", revision, sequence };
}

/**
 * PRD_ACTIVITIES 9.2: *"Une définition personnalisée est conservée définitivement, y compris
 * lorsqu'aucune séance ne l'utilise plus. Elle n'est jamais supprimée par la suppression d'une
 * séance."*
 *
 * So a delete is refused rather than journalled, exactly as the health profile's is, and for a
 * reason of the same kind: it would create a state the domain does not have. It would also create
 * one no client could apply — `strength_exercises.exercise_definition_id` holds a `RESTRICT`
 * foreign key onto `exercise_definitions`, so a phone receiving this tombstone would abort the
 * transaction that carries its cursor and stop synchronising on a page it can never get past.
 *
 * The V1 offers no screen that could produce one, and `SyncOutbox` mints no delete for this
 * aggregate. This exists so that an agent, or a future client, is told why instead of quietly
 * breaking every phone that pulls afterwards.
 */
function refuseDelete(aggregateId: string): ApplyOutcome {
  return {
    status: "rejected",
    error: mueError(
      "sync.invalid_payload",
      "A personal exercise definition is kept for ever (PRD_ACTIVITIES 9.2) and has no deletion.",
      false,
      { aggregateType: "customExerciseDefinition", aggregateId },
    ),
  };
}

export const customExerciseDefinitionHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return mutation.op === "upsert"
      ? applyUpsert(tx, context, mutation, now)
      : refuseDelete(mutation.aggregateId);
  },

  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ id: customExercises.id, createdAt: customExercises.createdAt })
      .from(customExercises)
      .where(
        and(
          eq(customExercises.userId, context.userId),
          inArray(customExercises.id, [...aggregateIds]),
        ),
      );
    for (const row of rows) found.set(row.id, row.createdAt);
    return found;
  },
};

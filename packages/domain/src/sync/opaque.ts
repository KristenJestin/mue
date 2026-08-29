import type { AggregateType, MutationEnvelope } from "@mue/contracts";
import { mueError } from "./errors";
import type { ApplyOutcome } from "./types";

/**
 * The rules six of the eight aggregates share, in one place, because they are the *same* rules
 * and not six similar ones.
 *
 * PRD section 13.3 covers "activités, recettes et autres agrégats" and PRD_FOOD 21.3 restates it
 * per food aggregate. Read together they say four things, and each is a decision below.
 *
 * ## 1. Two creations with different identifiers coexist
 *
 * Structural, and it needs no code: the identifier is the primary key, so two sessions with two
 * UUIDs are two rows. It is worth stating only because it is what makes the *absence* of a merge
 * heuristic correct rather than lazy.
 *
 * ## 2. An update on the last known revision is accepted; one on an older revision is detected
 *    as concurrent
 *
 * Detected, and then resolved — the two are different steps, and section 13.3 goes on to say how
 * the resolution goes. So a stale base is **not** a rejection here. What detection buys is what
 * the next rule spends: the replaced version is journalled, so the concurrency is visible in the
 * audit rather than only in the moment.
 *
 * ## 3. A non-destructive addition to a collection identified by UUID is merged
 *
 * **Inapplicable, and deliberately so.** It reads as though it were written for an activity's
 * exercises, and it cannot be: `StrengthDraftEditor.persistableExercises` mints a fresh id for
 * every exercise on every save and `ActivityDao.saveDetail` deletes and reinserts the whole tree,
 * so a child id is not stable across two writes of the same session. Merging on those ids would
 * duplicate every exercise on every edit — the rule's own precondition, "identifiée par UUID",
 * is what fails. The same is true of a recipe's ingredients, and PRD_FOOD 21.3 says so outright:
 * *"les ingrédients ne sont pas fusionnés ligne à ligne"*.
 *
 * So these aggregates are opaque. The payload replaces the row whole, which is exactly what
 * `activity_sessions` and `recipes` are shaped for.
 *
 * ## 4. Two concurrent modifications of the same field or child: the last accepted mutation
 *    becomes active, and the replaced version stays audited
 *
 * Which, for an aggregate replaced whole, is the only rule left — every field of it moves
 * together. So this is what [applyOpaqueUpsert]-shaped handlers do, and it is what PRD_FOOD 21.3
 * states word for word for all four food aggregates: *"dernière mutation acceptée"*.
 *
 * `sync.revision_conflict` is therefore produced by nothing in this build, and that is a
 * conclusion rather than an omission. The code stays in `MUE_ERROR_CODES` because a client must
 * keep degrading gracefully on an error it does not produce, and because a future aggregate
 * whose children *do* carry stable ids would have a genuine conflict to report.
 *
 * "Reste audité" is true without qualification here: `appendToJournal` writes a snapshot of every
 * accepted version and `retention.ts` never sweeps `sync_journal`.
 */

/** What every one of these handlers needs to know about the row it is about to replace. */
export interface OpaqueState {
  readonly revision: bigint;
  readonly deletedAt: Date | null;
}

/**
 * A tombstone the author did not know about.
 *
 * FR-SYNC-005 exists so an offline copy cannot resurrect a deletion, and section 13.3's closing
 * rule says a restoration must be an explicit mutation based on the *current* tombstone. So an
 * upsert is refused unless its `baseRevision` is the tombstone's own revision — which is
 * precisely the author having already received the delete and having chosen to undo it.
 *
 * This is `measurement.ts`'s rule, lifted verbatim rather than reimplemented, because the
 * argument for it does not mention weights anywhere.
 */
export function refusesResurrection(
  state: OpaqueState | undefined,
  baseRevision: string | null,
): boolean {
  return (
    state !== undefined && state.deletedAt !== null && baseRevision !== state.revision.toString()
  );
}

export function deletedRejection(
  aggregateType: AggregateType,
  aggregateId: string,
  state: OpaqueState,
  what: string,
): ApplyOutcome {
  return {
    status: "rejected",
    error: mueError(
      "sync.aggregate_deleted",
      `This ${what} was deleted. Restore it with a mutation based on the tombstone revision.`,
      false,
      { aggregateType, aggregateId, currentRevision: state.revision.toString() },
    ),
  };
}

/**
 * The guard every handler opens with.
 *
 * The registry dispatches on `aggregateType` and the envelope's upsert arm pins each payload to
 * its own type, so this is unreachable — and it is kept anyway, in every handler, for the reason
 * `measurement.ts` gives: it is also what narrows the union for the compiler, so a second
 * aggregate whose payload happened to fit these columns could not be written into them by
 * mistake.
 */
export function misroutedRejection(
  aggregateType: AggregateType,
  aggregateId: string,
): ApplyOutcome {
  return {
    status: "rejected",
    error: mueError(
      "sync.unknown_aggregate_type",
      `This mutation was routed to the ${aggregateType} handler and does not belong to it.`,
      false,
      { aggregateType, aggregateId },
    ),
  };
}

/** The next revision of an aggregate, whether or not it exists yet. */
export function nextRevision(state: OpaqueState | undefined): bigint {
  return (state?.revision ?? 0n) + 1n;
}

/** Narrowed once, so a handler body reads as though the union had already been resolved. */
export type UpsertOf<T extends AggregateType> = Extract<
  MutationEnvelope,
  { op: "upsert"; aggregateType: T }
>;

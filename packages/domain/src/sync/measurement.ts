import {
  type BodyCompositionV1,
  type MeasurementPayloadV1,
  type MueError,
  type MutationEnvelope,
} from "@mue/contracts";
import { type Transaction, appendToJournal, schema } from "@mue/db";
import { and, eq, inArray } from "drizzle-orm";
import { recalculateBodyComposition } from "../body-composition";
import { mueError } from "./errors";
import type { AggregateHandler, ApplyOutcome, SyncContext } from "./types";

const { bodyComposition, measurements } = schema;

/**
 * The rules of PRD section 13.2, and the only implementation of them.
 *
 * The business key is the local date, in Room and in `mue_app.measurements`
 * alike, so convergence is structural: two devices recording a weight for the
 * same day address the same row and cannot produce a second measurement for it.
 *
 * There is deliberately no revision conflict on the happy path. Section 13.2
 * says a newly accepted mutation *replaces* the current value and that the last
 * mutation the server accepts becomes the active version -- last-write-wins,
 * with the replaced version left in the journal for audit. `baseRevision` is
 * therefore recorded and, on a live aggregate, never a reason to reject;
 * `sync.revision_conflict` belongs to section 13.3's aggregates, which V1 does
 * not carry.
 *
 * PRD_SCALE 22 adds three things to that aggregate -- a business provenance, the
 * raw impedance and an optional composition -- and changes none of the rules
 * above. The clause it does add is the one this file spends most of its length
 * on: *"les valeurs dérivées fournies par le client ne font pas autorité"*.
 */

interface MeasurementState {
  readonly revision: bigint;
  readonly deletedAt: Date | null;
  readonly weightCg: number;
}

async function readState(
  tx: Transaction,
  userId: string,
  date: string,
): Promise<MeasurementState | undefined> {
  const rows = await tx
    .select({
      revision: measurements.revision,
      deletedAt: measurements.deletedAt,
      weightCg: measurements.weightCg,
    })
    .from(measurements)
    .where(and(eq(measurements.userId, userId), eq(measurements.date, date)));
  return rows[0];
}

/**
 * A tombstone the author did not know about. FR-SYNC-005 exists so that an
 * offline copy cannot resurrect a deletion, and section 13.3's closing rule
 * says a restoration must be an explicit mutation based on the *current*
 * tombstone. So an upsert is refused unless its `baseRevision` is the
 * tombstone's revision -- which is precisely the phone having already received
 * the delete and having chosen to undo it.
 */
function refusesResurrection(state: MeasurementState, baseRevision: string | null): boolean {
  return state.deletedAt !== null && baseRevision !== state.revision.toString();
}

/**
 * The four estimates PRD_SCALE 22 says a client does not have the last word on.
 *
 * The other six fields of a composition are its *snapshot* -- the formula named,
 * the weight, the height, the age at the date of the weighing and the sex the
 * equation was fed -- and the server does not second-guess those. FR-BODY-004 is
 * explicit that they are the inputs actually used, and that changing a profile
 * afterwards *"ne réécrit pas silencieusement l'historique"*: re-deriving a
 * height or an age from today's profile would rewrite exactly that history, for
 * a weighing taken years ago. So the snapshot is testimony, the four derived
 * values are arithmetic, and only arithmetic is redone here.
 */
const DERIVED_FIELDS = [
  "bodyFatDeciPercent",
  "fatFreeMassCg",
  "bodyWaterDeciPercent",
  "restingEnergyKcal",
] as const satisfies readonly (keyof BodyCompositionV1)[];

/** The derived fields on which the client's composition and the server's disagree. */
function divergentFields(
  claimed: BodyCompositionV1,
  recomputed: BodyCompositionV1,
): readonly string[] {
  return DERIVED_FIELDS.filter((field) => claimed[field] !== recomputed[field]);
}

/** The same payload with its composition removed, for BR-SCALE-007 and FR-BODY-001. */
function withoutComposition(payload: MeasurementPayloadV1): MeasurementPayloadV1 {
  const { bodyComposition: _dropped, ...rest } = payload;
  return rest;
}

/**
 * What the server made of a submitted composition.
 *
 * `payload` is what is *stored and journalled*, which is not always what
 * arrived: see [reconcileComposition].
 */
type Reconciliation =
  | { readonly status: "accepted"; readonly payload: MeasurementPayloadV1 }
  | { readonly status: "rejected"; readonly error: MueError };

/**
 * An operational trace of a composition the server did not take at face value.
 *
 * It names the outcome and the fields, never a value. PRD section 16 keeps
 * complete health payloads out of technical logs by default, and section 12.5
 * says the same thing about error messages for the same reason: the four
 * estimates, the impedance and the date of a weighing are all personal data, and
 * a log line is the last place any of them needs to appear. What an operator
 * needs is that a divergence *happened* and which quantity it was about -- the
 * numbers are in `sync_journal`, where they are access-controlled.
 */
function reportCorrection(reason: string): void {
  console.warn(`[sync] measurement composition not taken as stated: ${reason}`);
}

/**
 * PRD_SCALE 22's recalculation: *"pour une écriture MCP comportant une impédance
 * et les entrées requises, le serveur recalcule les résultats avec la version
 * demandée et rejette toute version inconnue. Les valeurs dérivées fournies par
 * le client ne font pas autorité."*
 *
 * ## It applies to every write, and not only to an agent's
 *
 * The PRD says "une écriture MCP" because that is the case it was written to
 * settle, and the wider reading is the one this server can actually implement:
 *
 * 1. **There is one write path.** An MCP tool and `POST /api/v1/sync/push` both
 *    reach `submitMutation`, which is the argument section 12.5 already made for
 *    the date policy -- and F-02 is what the narrow reading looks like in
 *    production: a rule enforced in the tools only, and a push endpoint quietly
 *    accepting what the tool beside it had just refused.
 * 2. **`origin.type` is not a fact the server can check.** It is a field of the
 *    envelope, filled in by whoever authored it. Making authority depend on it
 *    would mean any caller opts out of the check by writing `"android"`, which
 *    is not a boundary, only the appearance of one.
 * 3. **The wide reading costs nothing when the two halves agree.** PRD_SCALE
 *    13.2 requires Kotlin and TypeScript to produce the same stored integers for
 *    one payload, and both replay `mue-foot-to-foot-v1.json` to prove it. So for
 *    a correct client this function returns the payload it was given, unchanged.
 *    If it ever does not, the divergence is the exact failure 13.2 exists to
 *    prevent, and a client's own screen is the worst place to discover it.
 *
 * ## Three outcomes, and why each is the one that loses the least
 *
 * - **An unknown formula set is rejected.** PRD_SCALE 22 asks for it in as many
 *   words, and `recalculateBodyComposition` evaluates it before anything else:
 *   answering with this build's numbers under a version the caller named would
 *   store, under a version it believes it understands, integers a different
 *   equation produced, and no later migration could tell the two apart. It is
 *   the one refusal here that costs the whole mutation, and it is safe to
 *   replay: this check reads no clock and no stored state, so a payload refused
 *   today is refused identically for ever. No Mue client can produce one --
 *   both halves mint `mue-foot-to-foot-v1` version 1 from a constant.
 * - **A composition the equations refuse is dropped, and the weighing stands.**
 *   An impedance that is absent or unusable, a missing input, an age or a BMI
 *   outside FR-BODY-001's domain, a physically impossible result: FR-BODY-001
 *   settles all of them the same way -- *"le poids est enregistré normalement et
 *   la composition est simplement absente"*. Rejecting the mutation instead
 *   would cost the weight **and** the impedance, the two quantities that were
 *   actually measured, for a fault in a value the server can compute itself.
 *   FR-BODY-004 is unambiguous about which of those is replaceable: *"les
 *   formules sont discutables et remplaçables, la mesure ne l'est pas"*.
 * - **Derived values that disagree are replaced, not refused.** *"Ne font pas
 *   autorité"* is a statement about whose number is stored, not about whether
 *   the weighing is admissible. The server's own values are written and
 *   journalled, so the author converges on them at its next pull -- the same
 *   mechanism `health-profile.ts` uses to return a merged profile rather than
 *   the submitted one.
 *
 * ## A divergence is visible rather than silent
 *
 * Two channels, and neither is a value in a log. The journal snapshot is the
 * *accepted* payload, so `sync_journal` -- which `retention.ts` never sweeps --
 * holds for ever what the server actually stood behind, and the author is told
 * by being handed it back. And [reportCorrection] states, at the moment it
 * happens, that a correction happened and what it was about.
 */
function reconcileComposition(payload: MeasurementPayloadV1, aggregateId: string): Reconciliation {
  const claimed = payload.bodyComposition;
  if (claimed === undefined) {
    // Nothing to check, and nothing to invent. An impedance with no composition
    // is the ordinary state of a weighing taken before a profile is complete
    // (BR-SCALE-008, FR-BODY-004); it is stored exactly as it arrived, and the
    // server does not compute a composition the author did not send. FR-BODY-006
    // makes that calculation a proposal a person accepts, never a silent one.
    return { status: "accepted", payload };
  }

  const result = recalculateBodyComposition(claimed.formulaId, claimed.formulaVersion, {
    weightCg: payload.weightCg,
    // The snapshot, not the current profile (FR-BODY-004).
    heightCm: claimed.inputHeightCm,
    ageYears: claimed.inputAgeYears,
    sex: claimed.inputSex,
    // The impedance lives on the measurement, so a composition whose parent
    // carries none has no input to be a composition of (BR-SCALE-008).
    impedanceOhm: payload.impedanceOhm ?? null,
  });

  if (result.outcome === "unknown-formula") {
    return {
      status: "rejected",
      error: mueError(
        "sync.invalid_payload",
        `This server does not implement the formula set ${JSON.stringify(result.formulaId)} version ${result.formulaVersion}, so it cannot recalculate the composition it carries (PRD_SCALE 22).`,
        false,
        {
          aggregateType: "measurement",
          aggregateId,
          field: "payload.bodyComposition.formulaId",
        },
      ),
    };
  }

  if (result.outcome !== "calculated") {
    reportCorrection(`${result.outcome}, composition dropped and the weight kept`);
    return { status: "accepted", payload: withoutComposition(payload) };
  }

  const recomputed = result.composition;
  const divergent = divergentFields(claimed, recomputed);
  if (divergent.length === 0) {
    // The expected case: the two implementations agree, so this whole function
    // is a no-op and the payload is stored exactly as the author stated it.
    return { status: "accepted", payload };
  }

  reportCorrection(`recalculated ${divergent.join(", ")}`);
  return { status: "accepted", payload: { ...payload, bodyComposition: recomputed } };
}

/**
 * BR-SCALE-007's second half, in the transaction that writes the weight.
 *
 * A payload with a composition replaces the stored one; a *complete* payload
 * without one removes it. The absence is an instruction and not a silence: a
 * measurement upsert always states the whole aggregate (section 12.2), so
 * "there is no composition here" is the author saying there is none for this
 * date -- a manual correction, or a weighing whose impedance the scale refused.
 */
async function writeComposition(
  tx: Transaction,
  userId: string,
  date: string,
  composition: BodyCompositionV1 | undefined,
): Promise<void> {
  if (composition === undefined) {
    await tx
      .delete(bodyComposition)
      .where(and(eq(bodyComposition.userId, userId), eq(bodyComposition.date, date)));
    return;
  }

  await tx
    .insert(bodyComposition)
    .values({ userId, date, ...composition })
    .onConflictDoUpdate({
      target: [bodyComposition.userId, bodyComposition.date],
      set: {
        formulaId: composition.formulaId,
        formulaVersion: composition.formulaVersion,
        inputWeightCg: composition.inputWeightCg,
        inputHeightCm: composition.inputHeightCm,
        inputAgeYears: composition.inputAgeYears,
        inputSex: composition.inputSex,
        bodyFatDeciPercent: composition.bodyFatDeciPercent,
        fatFreeMassCg: composition.fatFreeMassCg,
        bodyWaterDeciPercent: composition.bodyWaterDeciPercent,
        restingEnergyKcal: composition.restingEnergyKcal,
      },
    });
}

async function applyUpsert(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "upsert" }>,
  now: Date,
): Promise<ApplyOutcome> {
  if (mutation.aggregateType !== "measurement") {
    // Unreachable: the registry dispatches on `aggregateType`, and the envelope's
    // upsert arm pins each payload to its own type. Kept because it is also what
    // narrows the union for the compiler -- so a second aggregate whose payload
    // happened to fit `measurements` could not be written into it by mistake.
    return {
      status: "rejected",
      error: mueError(
        "sync.unknown_aggregate_type",
        "This mutation was routed to the measurement handler and does not belong to it.",
        false,
        { aggregateId: mutation.aggregateId },
      ),
    };
  }
  const payload: MeasurementPayloadV1 = mutation.payload;
  const state = await readState(tx, context.userId, mutation.aggregateId);

  if (state !== undefined && refusesResurrection(state, mutation.baseRevision)) {
    return {
      status: "rejected",
      error: mueError(
        "sync.aggregate_deleted",
        "This measurement was deleted. Restore it with a mutation based on the tombstone revision.",
        false,
        {
          aggregateType: "measurement",
          aggregateId: mutation.aggregateId,
          currentRevision: state.revision.toString(),
        },
      ),
    };
  }

  const reconciled = reconcileComposition(payload, mutation.aggregateId);
  if (reconciled.status === "rejected") {
    return { status: "rejected", error: reconciled.error };
  }
  const accepted = reconciled.payload;

  const revision = (state?.revision ?? 0n) + 1n;
  await tx
    .insert(measurements)
    .values({
      userId: context.userId,
      date: accepted.date,
      weightCg: accepted.weightCg,
      // Absent means `manual` on the wire, and the column says so rather than
      // holding a null nobody could interpret (PRD_SCALE 21.1). A default here
      // and a default in the migration are the same fact stated twice, which is
      // what makes the whole pre-scale history read as `manual` too.
      sourceType: accepted.sourceType ?? "manual",
      impedanceOhm: accepted.impedanceOhm ?? null,
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
      target: [measurements.userId, measurements.date],
      // `created_at` is absent on purpose: it is the instant of the first
      // accepted version and never moves again.
      set: {
        weightCg: accepted.weightCg,
        sourceType: accepted.sourceType ?? "manual",
        impedanceOhm: accepted.impedanceOhm ?? null,
        revision,
        updatedAt: now,
        deletedAt: null,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  // Same transaction as the weight, which is PRD_SCALE 21.1's requirement in one
  // line: *"créer ou remplacer un poids écrit le `Measurement` complet dans une
  // seule transaction"*. There is no state in which a date carries the new
  // weight and the previous composition.
  await writeComposition(tx, context.userId, accepted.date, accepted.bodyComposition);

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "measurement",
    aggregateId: mutation.aggregateId,
    operation: "upsert",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    // A snapshot of what was accepted, not a pointer to the current row. This
    // is what lets a pull at sequence N return what N carried, what lets
    // section 12.4 reject an unsupported version for the exact change carrying
    // it, and what keeps the replaced version auditable (sections 13.1, 13.2).
    //
    // "Accepted" is the operative word since PRD_SCALE 22: where the server
    // recalculated a composition or dropped one the equations refuse, this is
    // the corrected payload and not the submitted one, so the author converges
    // on what was actually stored instead of believing its own numbers stood.
    // `health-profile.ts` journals a merged profile for the identical reason.
    payload: accepted,
    deletedAt: null,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });

  return { status: "applied", revision, sequence };
}

/**
 * A delete never erases: it writes a tombstone (FR-SYNC-005), and the row stays
 * until the documented retention sweep removes it.
 *
 * A delete for a date the server has never seen still writes one. The phone's
 * outbox is UUIDv7-ordered so the upsert normally arrives first, but if it does
 * not, refusing the delete would leave the client with a mutation it can never
 * drain, and journalling it without a row would leave nothing to stop a later
 * offline upsert from resurrecting it. `weight_cg` is not nullable, so such a
 * row carries 0 -- a value outside the domain bounds of `Weight`, and one no
 * reader ever sees: every read filters `deleted_at is null`, and a delete
 * change carries `payload: null` on the wire.
 *
 * ## The composition goes, and the database cannot do it
 *
 * BR-SCALE-007: deleting a measurement deletes its composition, atomically.
 * `body_composition` declares `on delete cascade`, and that cascade fires on a
 * *row* deletion -- which this is not. A tombstone is an `UPDATE`, so the child
 * would survive its parent's deletion and be handed back the day the date was
 * restored, describing a weighing that no longer exists. The removal is
 * therefore explicit, in this transaction, and the cascade covers the other
 * deletion the system performs: `retention.ts` hard-deleting expired tombstones.
 *
 * `weight_cg`, `source_type` and `impedance_ohm` are deliberately left as they
 * were. They are invisible -- every read filters `deleted_at is null` and a
 * tombstone carries no payload -- and keeping them means the row still says what
 * was deleted. A composition cannot be kept the same way: it is a separate row
 * with a foreign key that would outlive a hard delete of its parent by nothing
 * but luck, and BR-SCALE-007 names it explicitly where it names no column.
 */
async function applyDelete(
  tx: Transaction,
  context: SyncContext,
  mutation: Extract<MutationEnvelope, { op: "delete" }>,
  now: Date,
): Promise<ApplyOutcome> {
  const state = await readState(tx, context.userId, mutation.aggregateId);
  const revision = (state?.revision ?? 0n) + 1n;

  await tx
    .insert(measurements)
    .values({
      userId: context.userId,
      date: mutation.aggregateId,
      weightCg: state?.weightCg ?? 0,
      revision,
      createdAt: now,
      updatedAt: now,
      deletedAt: now,
      originType: mutation.origin.type,
      originId: mutation.origin.id,
      lastMutationId: mutation.mutationId,
      payloadSchemaVersion: mutation.payloadSchemaVersion,
    })
    .onConflictDoUpdate({
      target: [measurements.userId, measurements.date],
      set: {
        revision,
        updatedAt: now,
        deletedAt: now,
        originType: mutation.origin.type,
        originId: mutation.origin.id,
        lastMutationId: mutation.mutationId,
        payloadSchemaVersion: mutation.payloadSchemaVersion,
      },
    });

  // BR-SCALE-007. See the note above: no cascade fires for a tombstone.
  await writeComposition(tx, context.userId, mutation.aggregateId, undefined);

  const sequence = await appendToJournal(tx, {
    userId: context.userId,
    aggregateType: "measurement",
    aggregateId: mutation.aggregateId,
    operation: "delete",
    revision,
    payloadSchemaVersion: mutation.payloadSchemaVersion,
    payload: null,
    deletedAt: now,
    originType: mutation.origin.type,
    originId: mutation.origin.id,
    mutationId: mutation.mutationId,
  });

  return { status: "applied", revision, sequence };
}

export const measurementHandler: AggregateHandler = {
  async apply(tx, context, mutation, now) {
    return mutation.op === "upsert"
      ? applyUpsert(tx, context, mutation, now)
      : applyDelete(tx, context, mutation, now);
  },

  async createdAtFor(handle, context, aggregateIds) {
    const found = new Map<string, Date>();
    if (aggregateIds.length === 0) return found;
    const rows = await handle.db
      .select({ date: measurements.date, createdAt: measurements.createdAt })
      .from(measurements)
      .where(
        and(eq(measurements.userId, context.userId), inArray(measurements.date, [...aggregateIds])),
      );
    for (const row of rows) found.set(row.date, row.createdAt);
    return found;
  },
};

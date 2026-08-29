import { z } from "zod";
import { HEIGHT_MAX_CM, HEIGHT_MIN_CM, sexSchema } from "./health-profile";
import { localDateSchema } from "./primitives";

/** Bounds copied from Android's `Weight`, which enforces them as domain invariants. */
export const WEIGHT_MIN_CENTIGRAMS = 3_000;
export const WEIGHT_MAX_CENTIGRAMS = 25_000;
export const WEIGHT_STEP_CENTIGRAMS = 5;

export const MEASUREMENT_PAYLOAD_VERSION_1 = 1;

/**
 * A fresh copy of the weight rule, because it is stated in two places and must be one rule.
 *
 * `weightCg` and `bodyComposition.inputWeightCg` are the same quantity — BR-SCALE-015 makes them
 * equal — so a bound tightened on one and not the other would be a payload the refinement below
 * accepts and one of the two fields does not. It is a function rather than a shared constant so
 * each call yields a distinct schema instance and the generated specification inlines both,
 * instead of hoisting a `$ref` to a component that describes no aggregate of its own.
 */
function weightCentigrams() {
  return z
    .int()
    .min(WEIGHT_MIN_CENTIGRAMS)
    .max(WEIGHT_MAX_CENTIGRAMS)
    .multipleOf(WEIGHT_STEP_CENTIGRAMS);
}

/**
 * Where a weight came from, as a *business* fact (PRD_SCALE 21.1, 22).
 *
 * The four values are `MeasurementSource.wireValue` on Android, frozen there for the same reason
 * they are frozen here: the whole pre-scale history was back-filled with `manual` by an additive
 * migration, so renaming one would orphan every row already written.
 *
 * **There is deliberately no field for which scale.** PRD_SCALE 16.2 and 22 are explicit: the
 * business provenance may be synchronised, but `sourceScaleId`, the Bluetooth address and the
 * advertised name never leave the phone. `sourceScaleId` would not even denote anything
 * elsewhere — it is a UUID this installation minted at pairing. This is an absent field and not
 * an optional one left empty, because an optional field is one that is eventually filled in.
 */
export const MEASUREMENT_SOURCE_TYPES = ["manual", "scale", "agent", "server"] as const;

export const measurementSourceTypeSchema = z.enum(MEASUREMENT_SOURCE_TYPES).meta({
  id: "MeasurementSourceType",
  description:
    "Business provenance of a weight (PRD_SCALE 21.1). The scale's local identifier, address and name are never carried (PRD_SCALE 16.2, 22).",
  examples: ["manual", "scale"],
});

/**
 * The widest impedance a two-byte reading can state, and the only upper bound worth pinning.
 *
 * FR-BODY-004 settles the size — *"un entier de deux octets ne coûte rien à conserver"* — and
 * FR-BODY-002 settles the floor: zero, a negative value or the driver's own no-reading marker are
 * an *absence*, never a value, so the wire admits strictly positive integers only. Nothing
 * narrower is asserted here. A foot-to-foot reading sits around 300–1200 ohm on the reference
 * device, but that is a fact about one hardware family, and refusing a legitimate reading from a
 * scale nobody has written a driver for yet would discard the one quantity that was actually
 * measured (FR-BODY-004: *"les formules sont discutables et remplaçables, la mesure ne l'est
 * pas"*).
 */
export const IMPEDANCE_MAX_OHM = 65_535;

/**
 * The body composition of PRD_SCALE 12.3 and 21.1 — an **estimate**, with the exact snapshot of
 * the inputs it was estimated from.
 *
 * ## Why it is nested here and is not an aggregate
 *
 * PRD_SCALE 22: *"`BodyComposition` n'est pas un agrégat synchronisé indépendant. Elle voyage
 * dans le payload complet de `Measurement`, qui porte seul les métadonnées communes de §12.1."*
 * BR-SCALE-006 — a composition is an optional child of a measurement and cannot exist alone — is
 * therefore structural rather than a rule someone enforces: there is no `aggregateType` for one,
 * no envelope branch, no tombstone, and no way to address one on its own. `AGGREGATE_TYPES` is
 * unchanged by this module for exactly that reason.
 *
 * It follows that the *absence* of this object in a complete payload is an instruction and not a
 * silence: BR-SCALE-007 makes it the order to remove whatever composition the date carried.
 *
 * ## What it does not carry, and why each omission is deliberate
 *
 * - **No `date`.** It would always be the parent's, and two copies of one fact eventually
 *   disagree. Android's `body_composition.date` is simultaneously the primary key and the foreign
 *   key onto `measurements(date)`; here the nesting says the same thing.
 * - **No impedance.** FR-BODY-004 and BR-SCALE-008 put it on the measurement, and PRD_SCALE 22
 *   repeats the consequence for the wire: it synchronises even for a weighing that has no
 *   composition, which is exactly the material FR-BODY-006's retroactive calculation needs on
 *   every other client. Filed here it would vanish in the one case that matters — the first
 *   weighings, before anyone has entered a sex.
 *
 * ## Every field is an integer
 *
 * PRD_SCALE 13.2 requires the Kotlin and the TypeScript implementations to produce **the same
 * stored integers** for the same payload. A serialised float would not guarantee that, and the
 * calculation keeps its decimal precision internally and rounds exactly once, on the way in.
 *
 * `formulaId` is a bounded string and not a literal `mue-foot-to-foot-v1`. Pinning today's
 * identifier would make the next published formula a *wire* break — a new payload schema version
 * for what PRD_SCALE 13.2 already versions inside the value — and FR-BODY-004 explicitly plans
 * for a later formula recomputing the history from this snapshot. Which identifiers a given
 * build will actually honour is a server rule (PRD_SCALE 22: *"rejette toute version
 * inconnue"*), enforced where the recalculation happens, not a shape.
 */
export const bodyCompositionV1Schema = z
  .object({
    formulaId: z
      .string()
      .min(1)
      .max(64)
      .regex(/^[a-z0-9][a-z0-9-]*$/, "expected a lowercase hyphenated formula identifier"),
    formulaVersion: z.int().positive(),
    /** Always equal to the parent's `weightCg`; the refinement below is what makes that true. */
    inputWeightCg: weightCentigrams(),
    /**
     * The same absolute bounds `healthProfilePayloadV1Schema` puts on a stated height.
     *
     * They may be reused because they are absolute. This is a journal snapshot that `pull`
     * re-parses on every page, so a bound that moved with anything — a clock, a profile, a
     * setting — would one day make an accepted change fail to parse and stop a cursor dead on
     * data the client already holds. A height in centimetres is not that kind of bound.
     */
    inputHeightCm: z.int().min(HEIGHT_MIN_CM).max(HEIGHT_MAX_CM),
    /**
     * Whole years **at the date of the weighing** (FR-BODY-004, FR-BODY-006).
     *
     * The bound is a plausibility bound and not FR-BODY-001's 20–75 domain. That domain belongs
     * to one formula: it is the range `mue-foot-to-foot-v1` was validated over, it decides
     * whether an estimate may be *computed*, and a later formula with a wider domain would find
     * its own valid snapshots refused by a wire that had frozen this one's.
     */
    inputAgeYears: z.int().min(0).max(150),
    inputSex: sexSchema,
    /**
     * Tenths of a percent, strictly between 0 and 100% (PRD_SCALE 13.2).
     *
     * Strict at both ends, as the source is: a body that is 0% or 100% fat is not a person, and
     * PRD_SCALE 13.2 is explicit that a result outside the bounds means *no composition is
     * recorded* rather than a value clamped into range — *"aucun résultat n'est ramené
     * artificiellement dans les bornes"*.
     */
    bodyFatDeciPercent: z.int().gt(0).lt(1_000),
    /** Hundredths of a kilogram. `0 < FFM ≤ weight`; the upper half is refined on the parent. */
    fatFreeMassCg: z.int().positive(),
    /**
     * Tenths of a percent, same rule as the fat percentage.
     *
     * PRD_SCALE 13.2 also asks for water mass ≤ weight, and this bound *is* that requirement:
     * water mass is a percentage of the same weight, so below 100% is below the weight.
     */
    bodyWaterDeciPercent: z.int().gt(0).lt(1_000),
    /** Mifflin–St Jeor, rounded to the kilocalorie. Strictly positive (PRD_SCALE 13.2). */
    restingEnergyKcal: z.int().positive(),
  })
  .meta({
    id: "BodyCompositionV1",
    description:
      "Estimated body composition for one weighing, with the exact snapshot of the inputs it was derived from (PRD_SCALE 12.3, 13.2, 21.1). An optional child of its measurement and never an aggregate of its own (BR-SCALE-006); `inputWeightCg` always equals the parent's `weightCg` (BR-SCALE-015) and `fatFreeMassCg` never exceeds it.",
  });

export type BodyCompositionV1 = z.infer<typeof bodyCompositionV1Schema>;

/**
 * One weight recorded for one calendar day, with everything the scale module produced for it.
 *
 * The weight is a whole count of hundredths of a kilogram, exactly as Android stores it.
 * A JSON float would reintroduce the drift that Android's integer unit exists to avoid,
 * so the wire keeps the integer and the mapper is an identity. This is also why the
 * decimal-string rule does not apply here: `weightCg` is a bounded domain value, not a
 * 64-bit counter.
 *
 * `date` repeats the aggregate identifier because section 12.2 requires an upsert to
 * carry the complete aggregate; a payload that only made sense next to its envelope
 * could not be replayed from the journal on its own.
 *
 * ## Why the scale module extends version 1 instead of minting version 2
 *
 * PRD_SCALE 22 adds three things to this payload — a business provenance, a raw impedance and an
 * optional composition — and the alternative was a `MEASUREMENT_PAYLOAD_VERSION_2` with a second
 * branch in `upsertMutationSchema`. Version 1 is extended, with all three fields `.optional()`,
 * and the reason is what a version number *does* in this protocol rather than what it looks like:
 *
 * 1. **A version is a stop, not a flag.** `pull.ts` compares each journal entry's
 *    `payloadSchemaVersion` against the versions the client declared, and one entry it did not
 *    declare turns the whole page into `upgrade_required` **with no `nextCursor`**. A version 2
 *    measurement would therefore stop that client's cursor dead — not only on measurements, but
 *    on every food, activity and profile change queued behind it in the journal. The cost of a
 *    bump is paid by aggregates that have nothing to do with the change.
 * 2. **Nothing here is unapplicable, which is the only thing a bump can mean.**
 *    `sync.upgrade_required` is the protocol's way of saying *you would lose data if you applied
 *    this*, and it earns that meaning by being reserved for renames, unit changes and removals.
 *    An older client that meets these three fields ignores them and still stores the weight
 *    correctly. Spending the one signal that means "you cannot read this" on "there is something
 *    new you do not read" leaves nothing to say the first thing with.
 * 3. **Every payload an older client can produce still parses.** `{ date, weightCg }` is still a
 *    complete, valid instance, so nothing migrates and no journalled snapshot has to be rewritten
 *    — which matters because journalled payloads are re-parsed constantly: `pull.ts` runs every
 *    entry it returns back through `syncChangeSchema`, and `health-profile.ts` re-parses an old
 *    snapshot to use as a merge base. A version bump would leave those old rows describing a
 *    generation nothing on the current path reads.
 * 4. **The union has no room for it.** `upsertMutationSchema` discriminates on `aggregateType`
 *    alone; two measurement branches would collide on that key. A second version needs a nested
 *    `discriminatedUnion("payloadSchemaVersion", …)` inside the measurement arm, mirrored on the
 *    change side, and mirrored again by hand in Kotlin's `MutationEnvelopeSerializer` and
 *    `SyncChangeSerializer`, which already read two discriminators without framework support and
 *    would then need a third. That is a large structural cost, and it would be owed every time a
 *    field is added.
 *
 * What a version 2 would have bought is the ability to *refuse* a client that round-trips a
 * measurement and drops the new fields — which is a real hazard, because BR-SCALE-007 makes a
 * complete payload without a composition the order to delete one. It does not survive the
 * comparison: such a client would simply be told to upgrade and stop synchronising altogether,
 * losing more than the composition it would have dropped, and no client that never produced a
 * composition can drop one. The day a change here really is unapplicable — a renamed field, a
 * changed unit — the bump is owed, and these three fields will not have spent it.
 *
 * ## Two refinements, and why they are refinements
 *
 * `.refine` is invisible in `openapi.json`, which `health-profile.ts` names as a genuine cost, so
 * both rules are restated in the `description` below where an Android or agent author can read
 * them. They cannot be anything else: each relates a child field to a parent field, and JSON
 * Schema has no way to say so. The precedent is `measurementUpsertMutationSchema`'s
 * `payload.date === aggregateId`, which is the same kind of rule made structural in the same way.
 */
export const measurementPayloadV1Schema = z
  .object({
    date: localDateSchema,
    weightCg: weightCentigrams(),
    /**
     * Absent, on the wire, means `manual`.
     *
     * It is `.optional()` and carries no `.default()`. A default would make the parsed value
     * differ from the value received, which would put `openapi.ts`'s `io: "input"` generation and
     * the shape a client actually gets out of step — and it would rewrite, on every re-parse,
     * journalled snapshots that were written before this field existed.
     */
    sourceType: measurementSourceTypeSchema.optional(),
    /**
     * The raw impedance in ohms, **on the measurement and not on the composition** (FR-BODY-004,
     * BR-SCALE-008).
     *
     * PRD_SCALE 22 states the consequence for the wire directly: it synchronises even for a
     * measurement that has no composition, so that FR-BODY-006's retroactive calculation has the
     * same material on every client. Absent means no usable reading was taken — BR-SCALE-005
     * makes a refusal by the scale an absence and never a value — so there is nothing here for a
     * `null` to say that omission does not.
     */
    impedanceOhm: z.int().positive().max(IMPEDANCE_MAX_OHM).optional(),
    bodyComposition: bodyCompositionV1Schema.optional(),
  })
  .refine(
    (payload) =>
      payload.bodyComposition === undefined ||
      payload.bodyComposition.inputWeightCg === payload.weightCg,
    {
      error: "bodyComposition.inputWeightCg must equal weightCg",
      path: ["bodyComposition", "inputWeightCg"],
    },
  )
  .refine(
    (payload) =>
      payload.bodyComposition === undefined ||
      payload.bodyComposition.fatFreeMassCg <= payload.weightCg,
    {
      error: "bodyComposition.fatFreeMassCg must not exceed weightCg",
      path: ["bodyComposition", "fatFreeMassCg"],
    },
  )
  .meta({
    id: "MeasurementPayloadV1",
    description:
      "Weight measurement, payload schema version 1. `sourceType`, `impedanceOhm` and `bodyComposition` are optional additions of PRD_SCALE 22: a payload carrying none of them is complete and valid. Two rules the JSON Schema cannot express are enforced on parse: `bodyComposition.inputWeightCg` must equal `weightCg` (BR-SCALE-015), and `bodyComposition.fatFreeMassCg` must not exceed it (PRD_SCALE 13.2). A complete payload with no `bodyComposition` removes the one already stored for that date (BR-SCALE-007).",
  });

export type MeasurementPayloadV1 = z.infer<typeof measurementPayloadV1Schema>;

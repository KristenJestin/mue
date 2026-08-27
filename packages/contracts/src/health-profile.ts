import { z } from "zod";

/**
 * The health profile of PRD section 13.4: height and birth date, one aggregate per account.
 *
 * ## Why the identifier is a constant
 *
 * Section 13.4 opens with "le profil constitue un agrégat unique" — the profile *is* one
 * aggregate, not one per device and not one per edit. So its identifier is a literal rather
 * than a minted UUID, for the same reason a measurement's identifier is its date: convergence
 * has to be structural. If each device minted a UUID for its own profile, two phones would
 * create two aggregates, both valid, both synchronised, and no merge rule in section 13.4
 * could ever say which one is *the* profile. A constant makes a rival row unrepresentable
 * rather than merely unlikely, and it is the same `'me'` Android's `health_profile` table and
 * `mue_app.health_profile`'s single-column primary key are already keyed by.
 */
export const HEALTH_PROFILE_AGGREGATE_ID = "me";

export const HEALTH_PROFILE_PAYLOAD_VERSION_1 = 1;

/**
 * Bounds copied from Android's `UserProfile.HEIGHT_RANGE_CM`, which enforces them as a domain
 * invariant and words them in `MueValidation.HEIGHT_ERROR`.
 */
export const HEIGHT_MIN_CM = 120;
export const HEIGHT_MAX_CM = 230;

/**
 * The century a birth date may fall in, as a pattern on the ISO year.
 *
 * The bound is deliberately *absolute* where Android's is relative. `MueValidation
 * .validateBirthDate` refuses a date in the future or more than 120 years back, both measured
 * against the phone's clock; that rule cannot be the server's. A payload is a journal snapshot
 * that section 12.3 requires to stay readable for as long as the journal exists, and `pull`
 * re-parses every entry it returns through `syncChangeSchema` — so a clock-relative bound would
 * one day make a change that was valid when it was accepted fail to parse, and stop a cursor
 * dead on data the client already holds. Validity has to be a function of the value alone.
 *
 * It is a pattern rather than a `.refine`, because a refinement is invisible in the generated
 * specification: a rule an Android or agent author cannot read in `openapi.json` is a rule they
 * will meet as a rejection instead. Combined with the calendar check `z.iso.date()` already
 * applies, this is what makes `1998-11-31` and `0001-01-01` both unrepresentable.
 */
export const BIRTH_DATE_YEAR_PATTERN = /^(?:19|20)\d{2}-/;

export const birthDateSchema = z.iso
  .date()
  .regex(BIRTH_DATE_YEAR_PATTERN, "expected a birth date in 1900-2099")
  .meta({
    id: "BirthDate",
    description:
      "ISO-8601 calendar date of birth, year 1900-2099. The bound is absolute so a journalled payload never expires; the author's own rule (not in the future, not more than 120 years back) is the client's, and this server does not re-adjudicate it (PRD section 14.4).",
    examples: ["1998-11-18"],
  });

/**
 * Height and birth date, both nullable, both always present.
 *
 * Nullable rather than optional, and that distinction carries section 13.4's field merge. An
 * upsert states the complete aggregate (section 12.2), so a `null` means "this author says the
 * field is empty" while an absent key would mean "this author did not mention it" — two
 * different facts the merge would have to tell apart. Making both fields required-and-nullable
 * removes the second one from the wire entirely: every upsert is a complete statement, and the
 * merge never has to guess which kind of silence it is looking at.
 *
 * There is no field mirroring the aggregate identifier the way `MeasurementPayloadV1` repeats
 * its `date`. A measurement repeats its identifier because the identifier carries information —
 * which day the weight belongs to — that would be lost when the payload is read back from the
 * journal on its own. The profile's identifier carries none: it is a constant known to every
 * reader, and repeating it would only create a second place for it to be wrong.
 */
export const healthProfilePayloadV1Schema = z
  .object({
    heightCm: z.int().min(HEIGHT_MIN_CM).max(HEIGHT_MAX_CM).nullable(),
    birthDate: birthDateSchema.nullable(),
  })
  .meta({
    id: "HealthProfilePayloadV1",
    description:
      "Health profile (PRD section 13.4), payload schema version 1. One aggregate per account, identified by the constant `me`. Both fields are nullable and both are always present: null is a stated empty value, never an omission.",
  });

export type HealthProfilePayloadV1 = z.infer<typeof healthProfilePayloadV1Schema>;

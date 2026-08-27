import { z } from "zod";
import { localDateSchema } from "./primitives";

/** Bounds copied from Android's `Weight`, which enforces them as domain invariants. */
export const WEIGHT_MIN_CENTIGRAMS = 3_000;
export const WEIGHT_MAX_CENTIGRAMS = 25_000;
export const WEIGHT_STEP_CENTIGRAMS = 5;

export const MEASUREMENT_PAYLOAD_VERSION_1 = 1;

/**
 * One weight recorded for one calendar day.
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
 */
export const measurementPayloadV1Schema = z
  .object({
    date: localDateSchema,
    weightCg: z
      .int()
      .min(WEIGHT_MIN_CENTIGRAMS)
      .max(WEIGHT_MAX_CENTIGRAMS)
      .multipleOf(WEIGHT_STEP_CENTIGRAMS),
  })
  .meta({
    id: "MeasurementPayloadV1",
    description: "Weight measurement, payload schema version 1.",
  });

export type MeasurementPayloadV1 = z.infer<typeof measurementPayloadV1Schema>;

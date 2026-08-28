import { describe, expect, test } from "bun:test";
import type { ZodType } from "zod";
import { cursorPayloadSchema, cursorSchema } from "./cursor";
import { mueErrorSchema } from "./errors";
import { CONTRACT_FIXTURES } from "./fixtures";
import {
  HEALTH_PROFILE_AGGREGATE_ID,
  HEIGHT_MAX_CM,
  HEIGHT_MIN_CM,
  healthProfilePayloadV1Schema,
} from "./health-profile";
import { IMPEDANCE_MAX_OHM, measurementPayloadV1Schema } from "./measurement";
import { aggregateMetaSchema } from "./meta";
import { mutationEnvelopeSchema, syncChangeSchema } from "./mutation";
import { revisionSchema, sequenceSchema } from "./primitives";
import { pullRequestSchema, pullResponseSchema, pushResponseSchema } from "./sync";

/**
 * A schema earns its place only if a value survives parse, serialisation and parse
 * again unchanged. Anything that silently adds, drops or coerces a field shows here.
 */
function expectRoundTrip<T>(schema: ZodType<T>, value: unknown): T {
  const parsed = schema.parse(value);
  const reparsed = schema.parse(JSON.parse(JSON.stringify(parsed)));
  expect(reparsed).toEqual(parsed);
  return parsed;
}

describe("fixtures", () => {
  for (const fixture of CONTRACT_FIXTURES) {
    test(`${fixture.file} round-trips`, () => {
      expectRoundTrip(fixture.validator, fixture.value);
    });
  }
});

describe("counters", () => {
  test("accept the full 64-bit range as a decimal string", () => {
    expect(sequenceSchema.parse("18446744073709551615")).toBe("18446744073709551615");
    expect(revisionSchema.parse("0")).toBe("0");
  });

  test("reject a JSON number, so no client can do arithmetic on a sequence", () => {
    expect(sequenceSchema.safeParse(42).success).toBe(false);
  });

  test("reject a leading zero, so one number has one representation", () => {
    expect(revisionSchema.safeParse("01").success).toBe(false);
  });

  test("reject a negative or fractional counter", () => {
    expect(sequenceSchema.safeParse("-1").success).toBe(false);
    expect(sequenceSchema.safeParse("1.0").success).toBe(false);
  });
});

describe("cursor", () => {
  test("is opaque base64url and round-trips through its payload", () => {
    const payload = expectRoundTrip(cursorPayloadSchema, { v: 1, seq: "9007199254740993" });
    const encoded = btoa(JSON.stringify(payload))
      .replaceAll("+", "-")
      .replaceAll("/", "_")
      .replaceAll("=", "");
    expect(cursorSchema.parse(encoded)).toBe(encoded);
    expect(JSON.parse(atob(encoded))).toEqual(payload);
  });

  test("rejects padding and base64 characters outside the url alphabet", () => {
    expect(cursorSchema.safeParse("eyJ2IjoxfQ==").success).toBe(false);
    expect(cursorSchema.safeParse("ey+2/joxfQ").success).toBe(false);
  });

  test("rejects an unknown cursor version rather than guessing its meaning", () => {
    expect(cursorPayloadSchema.safeParse({ v: 2, seq: "1" }).success).toBe(false);
  });
});

describe("measurement payload v1", () => {
  test("accepts both domain bounds", () => {
    expectRoundTrip(measurementPayloadV1Schema, { date: "2026-01-01", weightCg: 3_000 });
    expectRoundTrip(measurementPayloadV1Schema, { date: "2026-01-01", weightCg: 25_000 });
  });

  test("rejects a weight outside the range or off the 0.05 kg step", () => {
    expect(
      measurementPayloadV1Schema.safeParse({ date: "2026-01-01", weightCg: 2_999 }).success,
    ).toBe(false);
    expect(
      measurementPayloadV1Schema.safeParse({ date: "2026-01-01", weightCg: 25_005 }).success,
    ).toBe(false);
    expect(
      measurementPayloadV1Schema.safeParse({ date: "2026-01-01", weightCg: 7_003 }).success,
    ).toBe(false);
  });

  test("rejects a float weight, which is what the integer unit exists to prevent", () => {
    expect(
      measurementPayloadV1Schema.safeParse({ date: "2026-01-01", weightCg: 78.45 }).success,
    ).toBe(false);
  });

  test("rejects a timestamp where a local date belongs", () => {
    expect(
      measurementPayloadV1Schema.safeParse({
        date: "2026-01-01T00:00:00.000Z",
        weightCg: 7_000,
      }).success,
    ).toBe(false);
  });

  /**
   * The claim that made extending version 1 preferable to minting a version 2: a payload written
   * by a build from before the scale module is still a *complete* instance of the same version.
   * If this ever fails, the three additions have become a break and they owe a version bump.
   */
  test("a payload from before PRD_SCALE 22 is still complete and unchanged by a round trip", () => {
    const before = { date: "2026-01-01", weightCg: 7_000 };
    expect(expectRoundTrip(measurementPayloadV1Schema, before)).toEqual(before);
  });

  /**
   * BR-SCALE-008 and PRD_SCALE 22: the impedance rides on the measurement, so it synchronises for
   * a weighing that has no composition — which is the whole material FR-BODY-006's retroactive
   * calculation needs on the other clients. The two fields are independently optional and this is
   * the combination that proves it.
   */
  test("an impedance without a composition is the ordinary state, not a contradiction", () => {
    expectRoundTrip(measurementPayloadV1Schema, {
      date: "2026-01-01",
      weightCg: 7_000,
      sourceType: "scale",
      impedanceOhm: 512,
    });
  });

  test("refuses an impedance that is an absence rather than a value (BR-SCALE-005)", () => {
    for (const impedanceOhm of [0, -1, 1.5, IMPEDANCE_MAX_OHM + 1]) {
      expect(
        measurementPayloadV1Schema.safeParse({ date: "2026-01-01", weightCg: 7_000, impedanceOhm })
          .success,
      ).toBe(false);
    }
  });

  test("refuses a provenance that is not one of the four, and never carries a scale", () => {
    expect(
      measurementPayloadV1Schema.safeParse({
        date: "2026-01-01",
        weightCg: 7_000,
        sourceType: "bluetooth",
      }).success,
    ).toBe(false);

    // PRD_SCALE 16.2 and 22: the device identifier is not a field this payload has, so a payload
    // carrying one is *stripped* rather than rejected — and the assertion is that it does not
    // survive. `z.object` is strip mode, which is exactly why every field that must cross the
    // wire is declared: an undeclared key is silently dropped, and that is how the sex used to be
    // lost on the way out of Android.
    const parsed = measurementPayloadV1Schema.parse({
      date: "2026-01-01",
      weightCg: 7_000,
      sourceType: "scale",
      sourceScaleId: "9d5a1c7e-0000-4000-8000-000000000000",
    });
    expect(Object.hasOwn(parsed, "sourceScaleId")).toBe(false);
  });

  /**
   * PRD_SCALE 13.2's own worked example, as the arithmetic and not as a shape.
   *
   * The published equations for 78.45 kg at 171 cm, 27 years old, male, 520 ohm give exactly
   * these four integers. `measurement-v1-valid.json` carries them, so this is simultaneously the
   * fixture's justification and the test vector the Kotlin and TypeScript implementations owe the
   * same answer to (PRD_SCALE 13.2: "un même payload doit produire les mêmes entiers stockés dans
   * les deux environnements").
   */
  test("accepts a full scale weighing with the composition its formulas actually produce", () => {
    const weightCg = 7_845;
    const heightCm = 171;
    const ageYears = 27;
    const impedanceOhm = 520;

    const weightKg = weightCg / 100;
    const ffmKg =
      13.055 +
      0.204 * weightKg +
      0.394 * ((heightCm * heightCm) / impedanceOhm) -
      0.136 * ageYears +
      8.125;
    const composition = {
      formulaId: "mue-foot-to-foot-v1",
      formulaVersion: 1,
      inputWeightCg: weightCg,
      inputHeightCm: heightCm,
      inputAgeYears: ageYears,
      inputSex: "male",
      bodyFatDeciPercent: Math.round(((weightKg - ffmKg) / weightKg) * 1_000),
      fatFreeMassCg: Math.round(ffmKg * 100),
      bodyWaterDeciPercent: Math.round(((ffmKg * 0.732) / weightKg) * 1_000),
      restingEnergyKcal: Math.round(10 * weightKg + 6.25 * heightCm - 5 * ageYears + 5),
    };

    expect([
      composition.fatFreeMassCg,
      composition.bodyFatDeciPercent,
      composition.bodyWaterDeciPercent,
      composition.restingEnergyKcal,
    ]).toEqual([5_567, 290, 519, 1_723]);

    expectRoundTrip(measurementPayloadV1Schema, {
      date: "2026-08-25",
      weightCg,
      sourceType: "scale",
      impedanceOhm,
      bodyComposition: composition,
    });
  });

  /** BR-SCALE-015, made unrepresentable rather than merely wrong. */
  test("refuses a composition whose input weight is not its parent's weight", () => {
    const parsed = measurementPayloadV1Schema.safeParse({
      ...fullMeasurement,
      bodyComposition: { ...fullComposition, inputWeightCg: 7_840 },
    });
    expect(parsed.success).toBe(false);
    expect(parsed.error?.issues[0]?.path).toEqual(["bodyComposition", "inputWeightCg"]);
  });

  /** PRD_SCALE 13.2: `0 < FFM ≤ poids`. Both halves, at the boundary on each side. */
  test("refuses a fat-free mass that is zero, negative or above the weight", () => {
    for (const fatFreeMassCg of [0, -1, fullMeasurement.weightCg + 5]) {
      expect(
        measurementPayloadV1Schema.safeParse({
          ...fullMeasurement,
          bodyComposition: { ...fullComposition, fatFreeMassCg, inputWeightCg: 7_845 },
        }).success,
      ).toBe(false);
    }
    // Equal to the weight is legal: it is a person with no fat mass, which the equation cannot
    // produce but the bound does not exclude, and clamping is forbidden either way.
    expect(
      measurementPayloadV1Schema.safeParse({
        ...fullMeasurement,
        bodyComposition: { ...fullComposition, fatFreeMassCg: fullMeasurement.weightCg },
      }).success,
    ).toBe(true);
  });

  /** PRD_SCALE 13.2: percentages strictly between 0 and 100, in tenths, both ends. */
  test("refuses a percentage at or beyond either end of the scale", () => {
    for (const field of ["bodyFatDeciPercent", "bodyWaterDeciPercent"] as const) {
      for (const value of [0, 1_000, -1, 1_001]) {
        expect([
          field,
          value,
          measurementPayloadV1Schema.safeParse({
            ...fullMeasurement,
            bodyComposition: { ...fullComposition, [field]: value },
          }).success,
        ]).toEqual([field, value, false]);
      }
    }
  });

  test("refuses a resting energy that is not strictly positive", () => {
    for (const restingEnergyKcal of [0, -1]) {
      expect(
        measurementPayloadV1Schema.safeParse({
          ...fullMeasurement,
          bodyComposition: { ...fullComposition, restingEnergyKcal },
        }).success,
      ).toBe(false);
    }
  });

  /**
   * BR-SCALE-006 from the other end. A composition cannot exist alone because there is no
   * aggregate type, no envelope branch and no identifier for one — so the only thing left to
   * assert here is that the nested object is genuinely whole-or-absent: a half-stated one does
   * not parse into a measurement that would then be written without it.
   */
  test("refuses a partial composition rather than accepting it with fields missing", () => {
    const { restingEnergyKcal: _dropped, ...partial } = fullComposition;
    expect(
      measurementPayloadV1Schema.safeParse({ ...fullMeasurement, bodyComposition: partial })
        .success,
    ).toBe(false);
  });
});

const fullComposition = {
  formulaId: "mue-foot-to-foot-v1",
  formulaVersion: 1,
  inputWeightCg: 7_845,
  inputHeightCm: 171,
  inputAgeYears: 27,
  inputSex: "male",
  bodyFatDeciPercent: 290,
  fatFreeMassCg: 5_567,
  bodyWaterDeciPercent: 519,
  restingEnergyKcal: 1_723,
};

const fullMeasurement = {
  date: "2026-08-25",
  weightCg: 7_845,
  sourceType: "scale",
  impedanceOhm: 520,
  bodyComposition: fullComposition,
};

/**
 * The health profile's rules, fed real values.
 *
 * That is the point of this block rather than a style. `SyncOutbox` once minted a UUIDv4 where
 * `mutationIdSchema` says `z.uuidv7()`, every push a phone made came back
 * `sync.invalid_payload`, and the Android drift detector could not see it — it compares
 * *shapes*, and a v4 and a v7 have the same shape. So every constraint below that narrows a
 * value rather than its type is exercised with a value that is really at the boundary, on both
 * sides of it.
 */
describe("health profile payload v1", () => {
  test("accepts the owner's own profile, the one his phone could not send", () => {
    expectRoundTrip(healthProfilePayloadV1Schema, { heightCm: 171, birthDate: "1998-11-18" });
  });

  test("accepts both height bounds and refuses the centimetre outside each", () => {
    expectRoundTrip(healthProfilePayloadV1Schema, {
      heightCm: HEIGHT_MIN_CM,
      birthDate: null,
    });
    expectRoundTrip(healthProfilePayloadV1Schema, {
      heightCm: HEIGHT_MAX_CM,
      birthDate: null,
    });
    for (const heightCm of [HEIGHT_MIN_CM - 1, HEIGHT_MAX_CM + 1]) {
      expect(healthProfilePayloadV1Schema.safeParse({ heightCm, birthDate: null }).success).toBe(
        false,
      );
    }
  });

  test("refuses a height that is not a whole number of centimetres", () => {
    expect(
      healthProfilePayloadV1Schema.safeParse({ heightCm: 171.5, birthDate: null }).success,
    ).toBe(false);
  });

  test("both fields may be null, and null is a stated value rather than an omission", () => {
    expectRoundTrip(healthProfilePayloadV1Schema, { heightCm: null, birthDate: null });
    expect(healthProfilePayloadV1Schema.safeParse({ heightCm: 171 }).success).toBe(false);
    expect(healthProfilePayloadV1Schema.safeParse({ birthDate: "1998-11-18" }).success).toBe(false);
  });

  test("refuses a date that reads as one and is not a day: 1998-11-31 has no thirty-first", () => {
    expect(
      healthProfilePayloadV1Schema.safeParse({ heightCm: 171, birthDate: "1998-11-31" }).success,
    ).toBe(false);
    expect(
      healthProfilePayloadV1Schema.safeParse({ heightCm: 171, birthDate: "2027-02-29" }).success,
    ).toBe(false);
    expectRoundTrip(healthProfilePayloadV1Schema, { heightCm: 171, birthDate: "2028-02-29" });
  });

  test("refuses a year outside 1900-2099, and a timestamp where a date belongs", () => {
    for (const birthDate of ["1899-12-31", "2100-01-01", "1998-11-18T00:00:00.000Z"]) {
      expect(healthProfilePayloadV1Schema.safeParse({ heightCm: 171, birthDate }).success).toBe(
        false,
      );
    }
  });

  /**
   * The sex of PRD_SCALE FR-PROFILE-007 and 22, and the shape that makes it an addition.
   *
   * It is `.optional()` where its two neighbours are nullable-and-required, and the reason is
   * that it arrived later: a required field would make every payload an existing client already
   * produces suddenly incomplete. This is the assertion that says the addition costs nothing.
   */
  test("a profile written before the sex existed is still complete", () => {
    const before = { heightCm: 171, birthDate: "1998-11-18" };
    expect(expectRoundTrip(healthProfilePayloadV1Schema, before)).toEqual(before);
  });

  test("states a sex when there is one, and omits it rather than nulling it when there is not", () => {
    for (const sex of ["female", "male"]) {
      expectRoundTrip(healthProfilePayloadV1Schema, { heightCm: 171, birthDate: null, sex });
    }
    // Absent is the unstated form. `null` is not a third state and does not parse, which is what
    // keeps the Kotlin DTO from writing a key the server would refuse.
    expect(
      healthProfilePayloadV1Schema.safeParse({ heightCm: null, birthDate: null, sex: null })
        .success,
    ).toBe(false);
  });

  test("refuses any value outside the two the equations accept", () => {
    for (const sex of ["other", "unknown", "", "Male", 0]) {
      expect(
        healthProfilePayloadV1Schema.safeParse({ heightCm: null, birthDate: null, sex }).success,
      ).toBe(false);
    }
  });
});

const upsert = {
  mutationId: "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
  aggregateType: "measurement",
  aggregateId: "2026-08-25",
  op: "upsert",
  baseRevision: null,
  payloadSchemaVersion: 1,
  payload: { date: "2026-08-25", weightCg: 7_000 },
  origin: { type: "android", id: "device-1" },
  clientOccurredAt: "2026-08-25T06:12:04.117Z",
};

describe("mutation envelope", () => {
  test("round-trips an upsert and a delete", () => {
    expectRoundTrip(mutationEnvelopeSchema, upsert);
    expectRoundTrip(mutationEnvelopeSchema, {
      ...upsert,
      op: "delete",
      baseRevision: "4",
      payload: null,
    });
  });

  test("an upsert without a payload and a delete with one are both unrepresentable", () => {
    expect(mutationEnvelopeSchema.safeParse({ ...upsert, payload: null }).success).toBe(false);
    expect(
      mutationEnvelopeSchema.safeParse({ ...upsert, op: "delete", baseRevision: "1" }).success,
    ).toBe(false);
  });

  test("a payload whose date contradicts the aggregate id is rejected", () => {
    expect(
      mutationEnvelopeSchema.safeParse({
        ...upsert,
        payload: { date: "2026-08-26", weightCg: 7_000 },
      }).success,
    ).toBe(false);
  });

  test("mutationId must be a UUIDv7, so the outbox drains in creation order", () => {
    expect(
      mutationEnvelopeSchema.safeParse({
        ...upsert,
        mutationId: "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
      }).success,
    ).toBe(false);
  });

  test("an unknown payload schema version is rejected rather than applied blindly", () => {
    expect(mutationEnvelopeSchema.safeParse({ ...upsert, payloadSchemaVersion: 2 }).success).toBe(
      false,
    );
  });
});

const profileUpsert = {
  mutationId: "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071",
  aggregateType: "healthProfile",
  aggregateId: HEALTH_PROFILE_AGGREGATE_ID,
  op: "upsert",
  baseRevision: null,
  payloadSchemaVersion: 1,
  payload: { heightCm: 171, birthDate: "1998-11-18" },
  origin: { type: "android", id: "device-1" },
  clientOccurredAt: "2026-08-25T06:12:04.902Z",
};

describe("the health profile is one aggregate per account", () => {
  test("round-trips the upsert of the single profile", () => {
    expectRoundTrip(mutationEnvelopeSchema, profileUpsert);
    expectRoundTrip(syncChangeSchema, {
      sequence: "9007199254740995",
      aggregateType: "healthProfile",
      aggregateId: HEALTH_PROFILE_AGGREGATE_ID,
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: { heightCm: 171, birthDate: "1998-11-18" },
      meta: {
        id: HEALTH_PROFILE_AGGREGATE_ID,
        revision: "2",
        createdAt: "2026-08-25T06:12:04.900Z",
        updatedAt: "2026-08-25T06:12:04.950Z",
        deletedAt: null,
        originType: "agent",
        originId: "agent-claude",
        lastMutationId: "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071",
      },
    });
  });

  /**
   * The rival row, refused by the wire. A second device that minted its own identifier for
   * "its" profile could not express the mutation at all, which is what makes section 13.4's
   * "un agrégat unique" structural rather than a rule the server has to remember.
   */
  test("refuses any aggregate id but the constant, so no rival profile is expressible", () => {
    for (const aggregateId of ["me-2", "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071", "2026-08-25"]) {
      expect(mutationEnvelopeSchema.safeParse({ ...profileUpsert, aggregateId }).success).toBe(
        false,
      );
    }
  });

  test("carries the payload its own aggregateType declares and no other", () => {
    expect(
      mutationEnvelopeSchema.safeParse({
        ...profileUpsert,
        payload: { date: "2026-08-25", weightCg: 7_000 },
      }).success,
    ).toBe(false);
    expect(
      mutationEnvelopeSchema.safeParse({
        ...upsert,
        payload: { heightCm: 171, birthDate: "1998-11-18" },
      }).success,
    ).toBe(false);
  });
});

describe("aggregate meta", () => {
  const meta = {
    id: "2026-08-25",
    revision: "4",
    createdAt: "2026-08-25T06:12:04.500Z",
    updatedAt: "2026-08-25T06:12:04.500Z",
    deletedAt: null,
    originType: "android",
    originId: "device-1",
    lastMutationId: "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
  };

  test("carries all eight of PRD section 12.1's fields", () => {
    expect(Object.keys(aggregateMetaSchema.parse(meta)).sort()).toEqual([
      "createdAt",
      "deletedAt",
      "id",
      "lastMutationId",
      "originId",
      "originType",
      "revision",
      "updatedAt",
    ]);
  });

  test("deletedAt is nullable but never absent, so a tombstone cannot be missed", () => {
    expectRoundTrip(aggregateMetaSchema, { ...meta, deletedAt: "2026-08-26T00:00:00.000Z" });
    const { deletedAt: _omitted, ...withoutDeletedAt } = meta;
    expect(aggregateMetaSchema.safeParse(withoutDeletedAt).success).toBe(false);
  });

  test("rejects an instant carrying an offset instead of Z", () => {
    expect(
      aggregateMetaSchema.safeParse({ ...meta, updatedAt: "2026-08-25T08:12:04.500+02:00" })
        .success,
    ).toBe(false);
  });
});

describe("MueError", () => {
  test("round-trips with every optional field present and with none", () => {
    expectRoundTrip(mueErrorSchema, {
      code: "server.internal",
      message: "Unexpected failure.",
      retryable: true,
    });
    expectRoundTrip(mueErrorSchema, {
      code: "sync.revision_conflict",
      message: "Concurrent update.",
      retryable: false,
      aggregateType: "measurement",
      aggregateId: "2026-08-25",
      field: "payload.weightCg",
      currentRevision: "7",
    });
  });

  test("accepts a code this build does not know, so a new code is not a parse failure", () => {
    expect(
      mueErrorSchema.safeParse({
        code: "sync.some_future_code",
        message: "From a newer server.",
        retryable: false,
      }).success,
    ).toBe(true);
  });

  test("rejects a code that is not a dotted lowercase identifier", () => {
    for (const code of ["SyncConflict", "sync", "sync..conflict", "1sync.conflict"]) {
      expect(mueErrorSchema.safeParse({ code, message: "x", retryable: false }).success).toBe(
        false,
      );
    }
  });
});

describe("push response", () => {
  test("carries revision and sequence on a duplicate, so a replay returns the stored result", () => {
    const parsed = expectRoundTrip(pushResponseSchema, {
      results: [
        {
          mutationId: "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
          status: "duplicate",
          revision: "10",
          sequence: "11",
        },
      ],
      serverTime: "2026-08-25T06:12:06.000Z",
    });
    const [result] = parsed.results;
    expect(result?.status === "duplicate" ? result.sequence : null).toBe("11");
  });

  test("a rejected result carries an error and no revision", () => {
    const parsed = pushResponseSchema.parse({
      results: [
        {
          mutationId: "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
          status: "rejected",
          error: { code: "sync.invalid_payload", message: "Bad.", retryable: false },
        },
      ],
      serverTime: "2026-08-25T06:12:06.000Z",
    });
    expect(parsed.results[0]).not.toHaveProperty("revision");
  });

  test("an empty results array is legal, because an empty push is not an error", () => {
    expect(
      pushResponseSchema.safeParse({ results: [], serverTime: "2026-08-25T06:12:06.000Z" }).success,
    ).toBe(true);
  });
});

describe("pull", () => {
  test("supportedSchemaVersions is required, which is what makes section 12.4 enforceable", () => {
    expect(pullRequestSchema.safeParse({ cursor: null, limit: 10 }).success).toBe(false);
    expect(
      pullRequestSchema.safeParse({
        cursor: null,
        limit: 10,
        supportedSchemaVersions: { measurement: [] },
      }).success,
    ).toBe(false);
  });

  test("limit is optional and bounded", () => {
    expect(
      pullRequestSchema.safeParse({ cursor: null, supportedSchemaVersions: { measurement: [1] } })
        .success,
    ).toBe(true);
    expect(
      pullRequestSchema.safeParse({
        cursor: null,
        limit: 501,
        supportedSchemaVersions: { measurement: [1] },
      }).success,
    ).toBe(false);
  });

  test("an aggregate type this build does not know is tolerated in the declaration", () => {
    expect(
      pullRequestSchema.safeParse({
        cursor: null,
        supportedSchemaVersions: { measurement: [1], recipe: [1, 2] },
      }).success,
    ).toBe(true);
  });

  test("upgrade_required cannot carry a cursor, so the cursor cannot advance", () => {
    const parsed = pullResponseSchema.parse({
      status: "upgrade_required",
      error: { code: "sync.upgrade_required", message: "Upgrade.", retryable: false },
      serverTime: "2026-08-25T06:12:07.000Z",
      lastAndroidSyncAt: null,
      nextCursor: "eyJ2IjoxfQ",
    });
    expect(parsed).not.toHaveProperty("nextCursor");
    expect(parsed).not.toHaveProperty("changes");
  });

  test("a page carries lastAndroidSyncAt, which may be null but is never absent", () => {
    const base = {
      status: "ok",
      changes: [],
      nextCursor: "eyJ2IjoxfQ",
      hasMore: false,
      serverTime: "2026-08-25T06:12:07.000Z",
    };
    expect(pullResponseSchema.safeParse(base).success).toBe(false);
    expect(pullResponseSchema.safeParse({ ...base, lastAndroidSyncAt: null }).success).toBe(true);
  });
});

describe("sync change", () => {
  test("a delete change still carries the metadata the tombstone row needs", () => {
    const change = expectRoundTrip(syncChangeSchema, {
      sequence: "12",
      aggregateType: "measurement",
      aggregateId: "2026-08-24",
      op: "delete",
      payloadSchemaVersion: 1,
      payload: null,
      meta: {
        id: "2026-08-24",
        revision: "10",
        createdAt: "2026-08-24T06:03:11.000Z",
        updatedAt: "2026-08-25T06:12:05.310Z",
        deletedAt: "2026-08-25T06:12:05.310Z",
        originType: "android",
        originId: "device-1",
        lastMutationId: "0198f0a1-9e8d-7c6b-b5a4-938271605f4e",
      },
    });
    expect(change.meta.deletedAt).not.toBeNull();
  });

  test("revision and sequence are independent numbers on the same change", () => {
    const change = syncChangeSchema.parse({
      sequence: "9007199254740993",
      aggregateType: "measurement",
      aggregateId: "2026-08-25",
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: { date: "2026-08-25", weightCg: 7_000 },
      meta: {
        id: "2026-08-25",
        revision: "2",
        createdAt: "2026-08-25T06:12:04.500Z",
        updatedAt: "2026-08-25T06:12:04.500Z",
        deletedAt: null,
        originType: "agent",
        originId: "agent-1",
        lastMutationId: "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
      },
    });
    expect(change.sequence).not.toBe(change.meta.revision);
  });
});

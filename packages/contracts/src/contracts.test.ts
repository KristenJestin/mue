import { describe, expect, test } from "bun:test";
import type { ZodType } from "zod";
import { cursorPayloadSchema, cursorSchema } from "./cursor";
import { mueErrorSchema } from "./errors";
import { CONTRACT_FIXTURES } from "./fixtures";
import { measurementPayloadV1Schema } from "./measurement";
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

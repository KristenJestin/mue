import { join } from "node:path";
import type { ZodType } from "zod";
import { cursorSchema } from "./cursor";
import { type MueError, mueErrorSchema } from "./errors";
import { type HealthProfilePayloadV1, healthProfilePayloadV1Schema } from "./health-profile";
import { type MeasurementPayloadV1, measurementPayloadV1Schema } from "./measurement";
import { type AggregateMeta, aggregateMetaSchema } from "./meta";
import { type MutationEnvelope, mutationEnvelopeSchema } from "./mutation";
import { canonicalJson } from "./openapi";
import {
  type PullRequest,
  type PullResponse,
  type PushRequest,
  type PushResponse,
  pullRequestSchema,
  pullResponseSchema,
  pushRequestSchema,
  pushResponseSchema,
} from "./sync";

/**
 * Where the JVM contract test reads them from. That test is offline: it parses each
 * fixture into its hand-written DTO, re-serialises and compares as a JSON tree, so a
 * field the server added and Kotlin ignores shows up as a diff, and a field Kotlin
 * requires and the server dropped fails to parse. No server, no network, no emulator.
 */
export const FIXTURE_RESOURCE_PATH = [
  "apps",
  "android",
  "app",
  "src",
  "test",
  "resources",
  "contract",
] as const;

export interface ContractFixture {
  /** File name under the fixture directory. */
  readonly file: string;
  /** The openapi.json component this instance is a member of. */
  readonly schema: string;
  readonly kind: "valid" | "edge" | "error";
  readonly description: string;
  readonly value: unknown;
  /** Checked at emit time, so no instance ships that its own schema rejects. */
  readonly validator: ZodType;
}

const DEVICE_ORIGIN = { type: "android", id: "device-7f3c1a04" } as const;

const MUTATION_ID = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6";
const TOMBSTONE_MUTATION_ID = "0198f0a1-9e8d-7c6b-b5a4-938271605f4e";

/**
 * Past 2^53, so a client that read the sequence as a JSON number instead of a decimal
 * string fails this fixture rather than a user's data three months later.
 */
const LARGE_SEQUENCE = "9007199254740993";
const NEXT_SEQUENCE = "9007199254740994";
const THIRD_SEQUENCE = "9007199254740995";

const CURSOR = toBase64Url(JSON.stringify({ v: 1, seq: LARGE_SEQUENCE }));

const validMeasurement = {
  date: "2026-08-25",
  weightCg: 7_845,
} satisfies MeasurementPayloadV1;

/** Two boundaries at once: the minimum legal weight, recorded on a leap day. */
const edgeMeasurement = {
  date: "2028-02-29",
  weightCg: 3_000,
} satisfies MeasurementPayloadV1;

const PROFILE_MUTATION_ID = "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071";

/**
 * The owner's own profile, as his phone journalled it and could not send: 171 cm, born on the
 * 18th of November 1998. It is here rather than a rounder invention because the bug this
 * aggregate exists to close was a *value* bug — a UUIDv4 where the schema said v7 — and a
 * fixture built from made-up numbers would have looked exactly as green.
 */
const validHealthProfile = {
  heightCm: 171,
  birthDate: "1998-11-18",
} satisfies HealthProfilePayloadV1;

/**
 * The cleared profile: both fields stated as null rather than omitted. It is the instance that
 * proves "the user emptied this" is expressible, which is what section 13.4's field-by-field
 * merge needs to tell apart from "this client did not mention it".
 */
const clearedHealthProfile = {
  heightCm: null,
  birthDate: null,
} satisfies HealthProfilePayloadV1;

const upsertMutation = {
  mutationId: MUTATION_ID,
  aggregateType: "measurement",
  aggregateId: validMeasurement.date,
  op: "upsert",
  baseRevision: "3",
  payloadSchemaVersion: 1,
  payload: validMeasurement,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-25T06:12:04.117Z",
} satisfies MutationEnvelope;

const deleteMutation = {
  mutationId: TOMBSTONE_MUTATION_ID,
  aggregateType: "measurement",
  aggregateId: "2026-08-24",
  op: "delete",
  baseRevision: "9",
  payloadSchemaVersion: 1,
  payload: null,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-25T06:12:05.004Z",
} satisfies MutationEnvelope;

/** A creation: `baseRevision` null is section 12.2's "si elle existe", and it is not zero. */
const healthProfileMutation = {
  mutationId: PROFILE_MUTATION_ID,
  aggregateType: "healthProfile",
  aggregateId: "me",
  op: "upsert",
  baseRevision: null,
  payloadSchemaVersion: 1,
  payload: validHealthProfile,
  origin: DEVICE_ORIGIN,
  clientOccurredAt: "2026-08-25T06:12:04.902Z",
} satisfies MutationEnvelope;

const liveMeta = {
  id: validMeasurement.date,
  revision: "4",
  createdAt: "2026-08-25T06:12:04.500Z",
  updatedAt: "2026-08-25T06:12:04.500Z",
  deletedAt: null,
  originType: "android",
  originId: DEVICE_ORIGIN.id,
  lastMutationId: MUTATION_ID,
} satisfies AggregateMeta;

const profileMeta = {
  id: "me",
  revision: "2",
  createdAt: "2026-08-25T06:12:04.900Z",
  updatedAt: "2026-08-25T06:12:04.950Z",
  deletedAt: null,
  originType: "android",
  originId: DEVICE_ORIGIN.id,
  lastMutationId: PROFILE_MUTATION_ID,
} satisfies AggregateMeta;

const tombstoneMeta = {
  id: "2026-08-24",
  revision: "10",
  createdAt: "2026-08-24T06:03:11.000Z",
  updatedAt: "2026-08-25T06:12:05.310Z",
  deletedAt: "2026-08-25T06:12:05.310Z",
  originType: "android",
  originId: DEVICE_ORIGIN.id,
  lastMutationId: TOMBSTONE_MUTATION_ID,
} satisfies AggregateMeta;

/** Every optional field absent: the shape a client must still parse. */
const minimalError = {
  code: "server.unavailable",
  message: "The server is restarting. Retry after a backoff.",
  retryable: true,
} satisfies MueError;

/** Carries currentRevision, so the client rebases instead of guessing. */
const conflictError = {
  code: "sync.revision_conflict",
  message: "The measurement for 2026-08-25 has moved on since baseRevision 3.",
  retryable: false,
  aggregateType: "measurement",
  aggregateId: "2026-08-25",
  currentRevision: "7",
} satisfies MueError;

/** PRD section 14.4: name the missing value, never invent one. */
const missingFieldError = {
  code: "sync.missing_required_field",
  message: "payload.weightCg is required for a measurement upsert.",
  retryable: false,
  aggregateType: "measurement",
  aggregateId: "2026-08-25",
  field: "payload.weightCg",
} satisfies MueError;

const upgradeRequiredError = {
  code: "sync.upgrade_required",
  message:
    "The server holds measurement payloads at schema version 2, which this client did not declare.",
  retryable: false,
  aggregateType: "measurement",
} satisfies MueError;

const pushRequest = {
  mutations: [upsertMutation, healthProfileMutation, deleteMutation],
} satisfies PushRequest;

/** All three outcomes in one body: a rejection never blocks the rest (FR-SYNC-007). */
const pushResponse = {
  results: [
    { mutationId: MUTATION_ID, status: "applied", revision: "4", sequence: LARGE_SEQUENCE },
    {
      mutationId: TOMBSTONE_MUTATION_ID,
      status: "duplicate",
      revision: "10",
      sequence: NEXT_SEQUENCE,
    },
    {
      mutationId: "0198f0a2-1111-7222-8333-444455556666",
      status: "rejected",
      error: conflictError,
    },
  ],
  serverTime: "2026-08-25T06:12:06.000Z",
} satisfies PushResponse;

const pullRequest = {
  cursor: CURSOR,
  limit: 100,
  supportedSchemaVersions: { healthProfile: [1], measurement: [1] },
} satisfies PullRequest;

const pullPage = {
  status: "ok",
  changes: [
    {
      sequence: LARGE_SEQUENCE,
      aggregateType: "measurement",
      aggregateId: validMeasurement.date,
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: validMeasurement,
      meta: liveMeta,
    },
    {
      sequence: NEXT_SEQUENCE,
      aggregateType: "measurement",
      aggregateId: tombstoneMeta.id,
      op: "delete",
      payloadSchemaVersion: 1,
      payload: null,
      meta: tombstoneMeta,
    },
    {
      sequence: THIRD_SEQUENCE,
      aggregateType: "healthProfile",
      aggregateId: "me",
      op: "upsert",
      payloadSchemaVersion: 1,
      payload: validHealthProfile,
      meta: profileMeta,
    },
  ],
  nextCursor: toBase64Url(JSON.stringify({ v: 1, seq: THIRD_SEQUENCE })),
  hasMore: false,
  serverTime: "2026-08-25T06:12:07.000Z",
  lastAndroidSyncAt: "2026-08-25T06:12:06.900Z",
} satisfies PullResponse;

/** No nextCursor at all, so the cursor cannot advance past data the client cannot apply. */
const pullUpgradeRequired = {
  status: "upgrade_required",
  error: upgradeRequiredError,
  serverTime: "2026-08-25T06:12:07.000Z",
  lastAndroidSyncAt: null,
} satisfies PullResponse;

export const CONTRACT_FIXTURES: readonly ContractFixture[] = [
  {
    file: "measurement-v1-valid.json",
    schema: "MeasurementPayloadV1",
    kind: "valid",
    description: "A typical weight measurement payload.",
    value: validMeasurement,
    validator: measurementPayloadV1Schema,
  },
  {
    file: "measurement-v1-edge.json",
    schema: "MeasurementPayloadV1",
    kind: "edge",
    description: "The minimum legal weight, recorded on a leap day.",
    value: edgeMeasurement,
    validator: measurementPayloadV1Schema,
  },
  {
    file: "health-profile-v1-valid.json",
    schema: "HealthProfilePayloadV1",
    kind: "valid",
    description: "The owner's own profile: 171 cm, born 1998-11-18.",
    value: validHealthProfile,
    validator: healthProfilePayloadV1Schema,
  },
  {
    file: "health-profile-v1-edge.json",
    schema: "HealthProfilePayloadV1",
    kind: "edge",
    description: "A cleared profile: both fields null and present, never absent.",
    value: clearedHealthProfile,
    validator: healthProfilePayloadV1Schema,
  },
  {
    file: "mutation-upsert-health-profile-v1.json",
    schema: "MutationEnvelope",
    kind: "valid",
    description: "The upsert the phone could not send, with its constant aggregate id.",
    value: healthProfileMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "mutation-upsert-measurement-v1.json",
    schema: "MutationEnvelope",
    kind: "valid",
    description: "An upsert, carrying the full aggregate.",
    value: upsertMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "mutation-delete-measurement.json",
    schema: "MutationEnvelope",
    kind: "edge",
    description: "A delete: null payload, and a baseRevision that must still be honoured.",
    value: deleteMutation,
    validator: mutationEnvelopeSchema,
  },
  {
    file: "aggregate-meta-live.json",
    schema: "AggregateMeta",
    kind: "valid",
    description: "Metadata for a live aggregate, deletedAt null.",
    value: liveMeta,
    validator: aggregateMetaSchema,
  },
  {
    file: "aggregate-meta-tombstone.json",
    schema: "AggregateMeta",
    kind: "edge",
    description: "Metadata for a tombstone, which the client keeps to block resurrection.",
    value: tombstoneMeta,
    validator: aggregateMetaSchema,
  },
  {
    file: "error-minimal.json",
    schema: "MueError",
    kind: "error",
    description: "Every optional field absent, and retryable.",
    value: minimalError,
    validator: mueErrorSchema,
  },
  {
    file: "error-revision-conflict.json",
    schema: "MueError",
    kind: "error",
    description: "Carries currentRevision so the client can rebase.",
    value: conflictError,
    validator: mueErrorSchema,
  },
  {
    file: "error-missing-required-field.json",
    schema: "MueError",
    kind: "error",
    description: "Names the missing field (PRD section 14.4).",
    value: missingFieldError,
    validator: mueErrorSchema,
  },
  {
    file: "error-upgrade-required.json",
    schema: "MueError",
    kind: "error",
    description: "An aggregate type but no id: the whole type is unreadable.",
    value: upgradeRequiredError,
    validator: mueErrorSchema,
  },
  {
    file: "push-request.json",
    schema: "PushRequest",
    kind: "valid",
    description: "An outbox batch of one upsert and one delete.",
    value: pushRequest,
    validator: pushRequestSchema,
  },
  {
    file: "push-response.json",
    schema: "PushResponse",
    kind: "valid",
    description: "applied, duplicate and rejected in one body.",
    value: pushResponse,
    validator: pushResponseSchema,
  },
  {
    file: "pull-request.json",
    schema: "PullRequest",
    kind: "valid",
    description: "A resumed pull, declaring the payload versions it can apply.",
    value: pullRequest,
    validator: pullRequestSchema,
  },
  {
    file: "pull-response-ok.json",
    schema: "PullResponse",
    kind: "valid",
    description: "A page of changes, with a sequence past 2^53.",
    value: pullPage,
    validator: pullResponseSchema,
  },
  {
    file: "pull-response-upgrade-required.json",
    schema: "PullResponse",
    kind: "edge",
    description: "The upgrade demand, which structurally carries no nextCursor.",
    value: pullUpgradeRequired,
    validator: pullResponseSchema,
  },
];

/** A manifest, so the JVM test enumerates the fixtures instead of listing them twice. */
export function buildFixtureManifest(): unknown {
  return {
    version: 1,
    fixtures: CONTRACT_FIXTURES.map((fixture) => ({
      file: fixture.file,
      schema: fixture.schema,
      kind: fixture.kind,
      description: fixture.description,
    })),
  };
}

/** The exact bytes each fixture file holds, keyed by file name. */
export function buildFixtureFiles(): Map<string, string> {
  const files = new Map<string, string>();
  for (const fixture of CONTRACT_FIXTURES) {
    fixture.validator.parse(fixture.value);
    files.set(fixture.file, canonicalJson(fixture.value));
  }
  files.set("index.json", canonicalJson(buildFixtureManifest()));
  return files;
}

export async function writeContractFixtures(directory: string): Promise<number> {
  const files = buildFixtureFiles();
  for (const [file, contents] of files) {
    await Bun.write(join(directory, file), contents);
  }
  return files.size;
}

export function fixtureDirectory(repoRoot: string): string {
  return join(repoRoot, ...FIXTURE_RESOURCE_PATH);
}

/** The repository root, from this file's location inside packages/contracts/src. */
export const REPO_ROOT = join(import.meta.dir, "..", "..", "..");

function toBase64Url(value: string): string {
  return btoa(value).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

// Parsed rather than asserted, so the literal cursors above stay bound to the one
// definition of the cursor's wire form.
export const FIXTURE_CURSOR = cursorSchema.parse(CURSOR);

if (import.meta.main) {
  const directory = fixtureDirectory(REPO_ROOT);
  const written = await writeContractFixtures(directory);
  console.log(`wrote ${written} contract fixtures to ${directory}`);
}

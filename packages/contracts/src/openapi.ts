import { z } from "zod";

// Evaluated for their side effect: every schema registers its component id with
// zod's global registry at definition time, and the generator reads that registry.
// Importing the barrel instead would make this module import itself.
import "./cursor";
import "./errors";
import "./health";
import "./measurement";
import "./meta";
import "./mutation";
import "./primitives";
import "./sync";

/** Bumped when `/api/v1` gains a compatible addition; a break needs `/api/v2`. */
export const OPENAPI_DOCUMENT_VERSION = "1.0.0";

const SCHEMA_REF_PREFIX = "#/components/schemas/";

/**
 * Arrays whose order carries no meaning, so sorting them removes a source of diff noise
 * without changing what the document says.
 *
 * Only these. Sorting every array would reorder `oneOf` branches, `servers` and
 * `examples`, all of which are authored in a deliberate order, and would silently
 * corrupt any tuple schema.
 */
const ORDER_INSENSITIVE_ARRAYS = new Set(["required", "enum"]);

/** Keys JSON Schema puts at the root of a standalone document and OpenAPI must not carry. */
const NON_COMPONENT_KEYS = new Set(["$schema", "$id"]);

function ref(id: string): { $ref: string } {
  return { $ref: `${SCHEMA_REF_PREFIX}${id}` };
}

function jsonBody(schemaId: string, description: string) {
  return {
    description,
    content: { "application/json": { schema: ref(schemaId) } },
  };
}

function buildComponentSchemas(): Record<string, unknown> {
  const { schemas } = z.toJSONSchema(z.globalRegistry, {
    uri: (id) => `${SCHEMA_REF_PREFIX}${id}`,
    target: "draft-2020-12",
    // OpenAPI 3.1 is JSON Schema 2020-12, so nothing is lost in translation. `input`
    // is the shape a client sends and, with no schema-level defaults or transforms in
    // this package, it is identical to the shape a client receives.
    io: "input",
  });

  const stripped: Record<string, unknown> = {};
  for (const [id, schema] of Object.entries(schemas)) {
    stripped[id] = stripKeys(schema, NON_COMPONENT_KEYS);
  }
  return stripped;
}

function stripKeys(value: unknown, keys: ReadonlySet<string>): unknown {
  if (Array.isArray(value)) {
    return value.map((entry) => stripKeys(entry, keys));
  }
  if (value === null || typeof value !== "object") {
    return value;
  }
  const result: Record<string, unknown> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (!keys.has(key)) {
      result[key] = stripKeys(entry, keys);
    }
  }
  return result;
}

export function buildOpenApiDocument(): Record<string, unknown> {
  return {
    openapi: "3.1.1",
    info: {
      title: "Mue Platform API",
      version: OPENAPI_DOCUMENT_VERSION,
      description:
        "The `/api/v1` contract the Mue Android client is written against, plus the operational health checks. TanStack Start server functions and MCP tools are deliberately outside it (PRD section 20.4).",
    },
    // Relative, because no Mue endpoint is ever published on the internet and a
    // hostname here would be an invitation to publish one.
    servers: [{ url: "/", description: "The private Mue Platform deployment." }],
    tags: [
      { name: "sync", description: "Bidirectional synchronisation (PRD section 11)." },
      { name: "health", description: "Liveness and readiness (PRD section 20.5)." },
    ],
    security: [{ androidBearer: [] }, { webSession: [] }],
    paths: {
      "/api/v1/sync/push": {
        post: {
          tags: ["sync"],
          operationId: "syncPush",
          summary: "Submit local mutations.",
          description:
            "Applies each mutation independently. A rejected mutation never blocks the rest (FR-SYNC-007), and replaying an already-applied `mutationId` returns the stored result (FR-SYNC-006).",
          requestBody: { required: true, ...jsonBody("PushRequest", "The outbox batch.") },
          responses: {
            "200": jsonBody("PushResponse", "One result per submitted mutation."),
            "400": jsonBody("ErrorResponse", "The batch itself is malformed."),
            "401": jsonBody("ErrorResponse", "Missing, invalid or revoked credentials."),
          },
        },
      },
      "/api/v1/sync/pull": {
        post: {
          tags: ["sync"],
          operationId: "syncPull",
          summary: "Read the change journal after a cursor.",
          description:
            "Returns a page of changes, or `upgrade_required` with no cursor at all when the server holds a payload version the caller did not declare (PRD sections 12.4 and 18).",
          requestBody: { required: true, ...jsonBody("PullRequest", "Cursor and capabilities.") },
          responses: {
            "200": jsonBody("PullResponse", "A page of changes, or an upgrade demand."),
            "400": jsonBody("ErrorResponse", "The cursor is unreadable."),
            "401": jsonBody("ErrorResponse", "Missing, invalid or revoked credentials."),
          },
        },
      },
      "/health/live": {
        get: {
          tags: ["health"],
          operationId: "healthLive",
          summary: "Is the process running.",
          security: [],
          responses: { "200": jsonBody("LivenessReport", "The process is running.") },
        },
      },
      "/health/ready": {
        get: {
          tags: ["health"],
          operationId: "healthReady",
          summary: "Can the process serve traffic.",
          security: [],
          responses: {
            "200": jsonBody("ReadinessReport", "Every dependency answered."),
            "503": jsonBody("ReadinessReport", "At least one dependency did not."),
          },
        },
      },
    },
    components: {
      schemas: buildComponentSchemas(),
      securitySchemes: {
        androidBearer: {
          type: "http",
          scheme: "bearer",
          description:
            "Better Auth bearer token, one session per device so revocation is per-device (PRD section 15.3).",
        },
        webSession: {
          type: "apiKey",
          in: "cookie",
          name: "better-auth.session_token",
          description: "HttpOnly, Secure, SameSite session cookie, checked server-side.",
        },
      },
    },
  };
}

function canonicalize(value: unknown, parentKey?: string): unknown {
  if (Array.isArray(value)) {
    const entries = value.map((entry) => canonicalize(entry));
    if (parentKey !== undefined && ORDER_INSENSITIVE_ARRAYS.has(parentKey)) {
      return entries.every((entry) => typeof entry === "string")
        ? [...(entries as string[])].sort()
        : entries;
    }
    return entries;
  }
  if (value === null || typeof value !== "object") {
    return value;
  }
  const result: Record<string, unknown> = {};
  for (const key of Object.keys(value).sort()) {
    result[key] = canonicalize((value as Record<string, unknown>)[key], key);
  }
  return result;
}

/**
 * Deterministic serialisation, so the one CI check guarding the Android contract emits
 * signal rather than a permanent diff that everyone learns to ignore.
 */
export function canonicalJson(value: unknown): string {
  return `${JSON.stringify(canonicalize(value), null, 2)}\n`;
}

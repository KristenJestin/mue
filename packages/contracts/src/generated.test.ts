import { describe, expect, test } from "bun:test";
import { join } from "node:path";
import { REPO_ROOT, buildFixtureFiles, buildFixtureManifest, fixtureDirectory } from "./fixtures";
import { CURRENT_PAYLOAD_SCHEMA_VERSIONS } from "./versions";
import { buildOpenApiDocument, canonicalJson } from "./openapi";

const OPENAPI_PATH = join(REPO_ROOT, "packages", "contracts", "openapi.json");

describe("openapi.json", () => {
  test("two generations are byte-identical", () => {
    expect(canonicalJson(buildOpenApiDocument())).toBe(canonicalJson(buildOpenApiDocument()));
  });

  test("the committed file matches the generator", async () => {
    const committed = await Bun.file(OPENAPI_PATH).text();
    expect(committed).toBe(canonicalJson(buildOpenApiDocument()));
  });

  test("every object key is sorted, at every depth", async () => {
    const document: unknown = JSON.parse(await Bun.file(OPENAPI_PATH).text());
    expect(unsortedKeyPaths(document)).toEqual([]);
  });

  test("component schemas carry no JSON Schema document keys", async () => {
    const text = await Bun.file(OPENAPI_PATH).text();
    expect(text).not.toContain('"$schema"');
    expect(text).not.toContain('"$id"');
  });

  test("both counters are described as separate components", async () => {
    const document = JSON.parse(await Bun.file(OPENAPI_PATH).text()) as {
      components: { schemas: Record<string, { description?: string }> };
    };
    const { Revision, Sequence } = document.components.schemas;
    expect(Revision?.description).toMatch(/never used as a cursor/);
    expect(Sequence?.description).toMatch(/never compared to a revision/);
  });
});

describe("android contract fixtures", () => {
  test("the emitted files match what is on disk", async () => {
    const directory = fixtureDirectory(REPO_ROOT);
    for (const [file, contents] of buildFixtureFiles()) {
      expect(await Bun.file(join(directory, file)).text()).toBe(contents);
    }
  });

  /**
   * Every payload the contract can carry, not just the first one. An aggregate added without
   * its own pair of instances would ship a wire shape the Android drift detector never reads,
   * which is the state `healthProfile` was in for as long as it had no branch at all.
   */
  test("the manifest names one valid and one edge instance per payload schema", () => {
    const manifest = buildFixtureManifest() as {
      fixtures: { file: string; schema: string; kind: string }[];
    };
    for (const schema of ["MeasurementPayloadV1", "HealthProfilePayloadV1"]) {
      const instances = manifest.fixtures.filter((f) => f.schema === schema);
      expect(instances.map((f) => f.kind).sort()).toEqual(["edge", "valid"]);
    }
    expect(manifest.fixtures.filter((f) => f.kind === "error").length).toBeGreaterThanOrEqual(4);
  });

  /**
   * The payload schemas of `CURRENT_PAYLOAD_SCHEMA_VERSIONS` and the instances on disk are the
   * same set. This is the assertion that would have failed on the day `healthProfile` was
   * added to `AGGREGATE_TYPES` with no fixture behind it.
   */
  test("every aggregate type the server declares has instances on disk", () => {
    const manifest = buildFixtureManifest() as { fixtures: { schema: string }[] };
    const schemas = new Set(manifest.fixtures.map((f) => f.schema));
    for (const aggregateType of Object.keys(CURRENT_PAYLOAD_SCHEMA_VERSIONS)) {
      const component = `${aggregateType.charAt(0).toUpperCase()}${aggregateType.slice(1)}PayloadV1`;
      expect([aggregateType, schemas.has(component)]).toEqual([aggregateType, true]);
    }
  });
});

/** Returns the dotted path of every object whose keys are not in ascending order. */
function unsortedKeyPaths(value: unknown, path = "$"): string[] {
  if (Array.isArray(value)) {
    return value.flatMap((entry, index) => unsortedKeyPaths(entry, `${path}[${index}]`));
  }
  if (value === null || typeof value !== "object") {
    return [];
  }
  const keys = Object.keys(value);
  const sorted = [...keys].sort();
  const paths = keys.every((key, index) => key === sorted[index]) ? [] : [path];
  return [
    ...paths,
    ...Object.entries(value).flatMap(([key, entry]) => unsortedKeyPaths(entry, `${path}.${key}`)),
  ];
}

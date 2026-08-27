import { describe, expect, test } from "bun:test";
import { resetSchemas } from "./testing";

describe("le garde-fou de resetSchemas", () => {
  test("refuse la base de développement, celle qu'un téléphone appaire", async () => {
    const handle = { config: { url: "postgres://mue:x@127.0.0.1:5433/mue_dev" } } as never;
    expect(resetSchemas(handle)).rejects.toThrow(/not a disposable database/);
  });

  test("nomme la base et dit quoi faire", async () => {
    const handle = { config: { url: "postgres://mue:x@127.0.0.1:5433/mue_dev" } } as never;
    expect(resetSchemas(handle)).rejects.toThrow(/mue_dev/);
  });
});

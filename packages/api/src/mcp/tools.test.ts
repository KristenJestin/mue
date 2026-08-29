import { describe, expect, test } from "bun:test";
import { MUE_SCOPES, type MueScope } from "@mue/auth";
import { z } from "zod";
import { decodeListCursor, encodeListCursor, InvalidCursorError } from "./cursor";
import { envelopeSchema, toolFailure, toolSuccess } from "./errors";
import { readAgentIdentity, IdentityError } from "./identity";
import {
  MUE_MCP_INSTRUCTIONS,
  MUE_MCP_PROTOCOL_VERSION,
  PRD_REQUESTED_PROTOCOL_VERSION,
} from "./protocol";
import { isToolPermitted, MUE_TOOLS, toolsForScopes } from "./tools";

const ALL_SCOPES: ReadonlySet<MueScope> = new Set(MUE_SCOPES);

describe("protocol revision", () => {
  test("targets the revision the SDK ships, not the one the PRD names", () => {
    // PLATFORM-CONTRACT decision 4 and section 5bis: `2026-07-28` exists in no shipping
    // SDK. This assertion is what keeps the divergence visible; the day it fails is the
    // day the PRD's revision became real and both constants can collapse into one.
    expect(MUE_MCP_PROTOCOL_VERSION).toBe("2025-11-25");
    expect(MUE_MCP_PROTOCOL_VERSION).not.toBe(PRD_REQUESTED_PROTOCOL_VERSION);
  });

  test("names no model, vendor or AI provider anywhere an agent can read", () => {
    // Section 14.1 and section 8.1: the server works with no OpenAI, Anthropic or
    // Google account, and nothing about it may hint otherwise.
    const surface = [
      MUE_MCP_INSTRUCTIONS,
      ...MUE_TOOLS.map((tool) => `${tool.name} ${tool.title} ${tool.description}`),
    ]
      .join(" ")
      .toLowerCase();
    for (const vendor of ["openai", "anthropic", "claude", "gpt", "gemini", "llm"]) {
      expect(surface).not.toContain(vendor);
    }
  });
});

describe("the tool catalogue", () => {
  test("sets all four standard annotations on every tool", () => {
    for (const tool of MUE_TOOLS) {
      // Section 14.1 asks for the standard set: read-only, destructive, idempotent and
      // external interaction. An absent hint is not an answer, so all four are checked.
      expect(typeof tool.annotations.readOnlyHint).toBe("boolean");
      expect(typeof tool.annotations.destructiveHint).toBe("boolean");
      expect(typeof tool.annotations.idempotentHint).toBe("boolean");
      expect(typeof tool.annotations.openWorldHint).toBe("boolean");
    }
  });

  test("marks a read-only tool read-only and never destructive", () => {
    for (const tool of MUE_TOOLS) {
      if (!tool.annotations.readOnlyHint) continue;
      expect(tool.annotations.destructiveHint).toBe(false);
      expect(tool.scopes.every((scope) => scope.endsWith(":read"))).toBe(true);
    }
  });

  test("declares no external interaction, because the server reaches no third party", () => {
    // Section 8.1: no relay, no tunnel, no model provider. `openWorldHint: true` would
    // tell an agent the opposite.
    for (const tool of MUE_TOOLS) expect(tool.annotations.openWorldHint).toBe(false);
  });

  test("requires a scope for every tool, and a write scope for every write tool", () => {
    for (const tool of MUE_TOOLS) {
      expect(tool.scopes.length).toBeGreaterThan(0);
      if (tool.annotations.readOnlyHint) continue;
      expect(tool.scopes.some((scope) => scope.endsWith(":write") || scope === "data:delete")).toBe(
        true,
      );
    }
  });

  test("exposes no parameter through which a table, a query or a path could pass", () => {
    // Sections 14.1 and 16: never expose the tables, raw SQL, the file system or the
    // process. Making that a property of the catalogue means no future tool can
    // reintroduce it without failing here.
    const forbidden =
      /sql|query|table|schema|column|where|order_?by|path|file|dir|command|exec|url/i;
    // The *parameter* name is what is checked, not the tool's own name prefixed to it. The
    // rule is about what a caller can pass; `mue.update_health_profile` contains "file"
    // inside "profile" and passes nothing anywhere. The tool name is still reported, so a
    // failure says which tool to look in.
    for (const tool of MUE_TOOLS) {
      for (const name of Object.keys(tool.inputSchema)) {
        const input = `${tool.name}.${name}`;
        expect({ input, safe: !forbidden.test(name) }).toEqual({ input, safe: true });
      }
    }
  });

  test("describes every input, so an agent can correct its own mistake", () => {
    // Section 14.1: "des descriptions et schemas suffisamment precis pour qu'un agent
    // choisisse l'outil et corrige ses erreurs".
    for (const tool of MUE_TOOLS) {
      expect(tool.description.length).toBeGreaterThan(80);
      for (const [name, schema] of Object.entries(tool.inputSchema)) {
        const description = (schema as z.ZodType).description ?? "";
        expect({ input: `${tool.name}.${name}`, described: description.length > 30 }).toEqual({
          input: `${tool.name}.${name}`,
          described: true,
        });
      }
    }
  });
});

describe("the scale module in the catalogue (PRD_SCALE 16.2, 22)", () => {
  const weightTools = (suffix: string) => MUE_TOOLS.filter((tool) => tool.name.endsWith(suffix));

  test("a body composition is reachable only as part of its weighing", () => {
    // BR-SCALE-006 and PRD_SCALE 22: *"il n'existe pas d'outil indépendant capable de créer une
    // composition orpheline."* This is the catalogue-level half of that -- one input in the
    // whole surface is named for a composition, and it belongs to the tool that writes the
    // weight. A future `mue.create_body_composition` fails here before it is ever called.
    const carriers = MUE_TOOLS.filter((tool) => "bodyComposition" in tool.inputSchema);
    expect(carriers.map((tool) => tool.name)).toEqual(["mue.upsert_weight_measurement"]);
    for (const tool of MUE_TOOLS) {
      const named = /composition/i.test(tool.name);
      expect({ name: tool.name, named }).toEqual({ name: tool.name, named: false });
    }
  });

  test("no input in the whole catalogue names a scale, an address or a device", () => {
    // PRD_SCALE 16.2: the local identifier, the Bluetooth address and the advertised name
    // never leave the phone. There is no column for any of them either, which the integration
    // test asserts against `information_schema`; this is the surface an agent can *speak*.
    //
    // `sourceType` is the exception the rule is built around and it is not matched here: it is
    // a value, not a parameter name, and it says a scale was involved without saying which.
    const identifying = /scaleid|scale_id|macaddress|bluetooth|advertised|deviceaddress/i;
    for (const tool of MUE_TOOLS) {
      for (const name of Object.keys(tool.inputSchema)) {
        const input = `${tool.name}.${name}`;
        expect({ input, identifying: identifying.test(name) }).toEqual({
          input,
          identifying: false,
        });
      }
    }
  });

  test("a composition takes the scope of the weighing it belongs to, and no scope of its own", () => {
    // PRD_SCALE 22 makes reading a composition as sensitive as reading a weight and writing one
    // a health-data write. Both are satisfied by the weight tools' own scopes, because a
    // composition is a field of that record and BR-SCALE-006 gives it no life apart from it. A
    // scope of its own would advertise a permission over an object that does not exist -- and
    // section 15.2's list is what a person is actually asked to consent to.
    for (const tool of weightTools("_weight_measurement").concat(
      weightTools("_weight_measurements"),
    )) {
      for (const scope of tool.scopes) {
        expect({ name: tool.name, scope }).toEqual({
          name: tool.name,
          scope: tool.annotations.readOnlyHint
            ? "weight:read"
            : tool.name.includes(".delete_") && scope === "data:delete"
              ? "data:delete"
              : "weight:write",
        });
      }
    }
    // And the sex, which joined the health profile rather than the weighing (PRD_SCALE 22).
    const profile = MUE_TOOLS.filter((tool) => tool.name.endsWith("_health_profile"));
    expect(profile).toHaveLength(2);
    for (const tool of profile) {
      expect({ name: tool.name, scopes: [...tool.scopes] }).toEqual({
        name: tool.name,
        scopes: [tool.annotations.readOnlyHint ? "profile:read" : "profile:write"],
      });
    }
    // No scope was invented for any of this: every one a tool declares is one section 15.2
    // lists and `MUE_SCOPES` implements, or nobody could be asked to grant it.
    for (const tool of MUE_TOOLS) {
      for (const scope of tool.scopes) expect(ALL_SCOPES.has(scope)).toBe(true);
    }
  });

  test("the sex is offered to an agent alongside the two fields it sits with", () => {
    // PRD_SCALE 22: *"le sexe rejoint l'agrégat `HealthProfile`"*, so it is read and written by
    // the profile tools and by nothing else. The `clear` flag is what keeps *"I emptied this"*
    // sayable without *"I did not mention this"* meaning the same -- the distinction section
    // 13.4's merge is built on, and the one whose absence deleted the field.
    const update = MUE_TOOLS.find((tool) => tool.name === "mue.update_health_profile");
    expect(Object.keys(update!.inputSchema)).toContain("sex");
    expect(Object.keys(update!.inputSchema)).toContain("clearSex");
    // And it says what it is for. FR-PROFILE-007 requires the field to justify itself wherever
    // it is offered: a sex asked for without a stated use reads as gratuitous collection.
    const description = (update!.inputSchema["sex"] as z.ZodType).description ?? "";
    expect(description.toLowerCase()).toContain("composition");
  });
});

describe("scope gating", () => {
  test("a read scope reaches no write tool", () => {
    // Section 22.5, at the unit level. The integration test proves the same thing
    // through a real client and a real token.
    const readOnly: ReadonlySet<MueScope> = new Set<MueScope>(["weight:read"]);
    const visible = toolsForScopes(readOnly).map((tool) => tool.name);

    expect(visible).toContain("mue.list_weight_measurements");
    for (const tool of MUE_TOOLS) {
      if (tool.annotations.readOnlyHint) continue;
      expect(visible).not.toContain(tool.name);
      expect(isToolPermitted(tool, readOnly)).toBe(false);
    }
  });

  test("every tool is reachable with the full scope set", () => {
    expect(toolsForScopes(ALL_SCOPES)).toHaveLength(MUE_TOOLS.length);
  });

  test("no scope at all reaches nothing", () => {
    expect(toolsForScopes(new Set())).toHaveLength(0);
  });
});

describe("the tool result envelope", () => {
  const schema = envelopeSchema(z.object({ value: z.int() }));

  test("validates a success", () => {
    const result = toolSuccess({ value: 1 });
    expect(schema.safeParse(result.structuredContent).success).toBe(true);
    expect(result.isError).toBeUndefined();
  });

  test("validates a business error too, which is why there is one envelope", () => {
    // The SDK client validates `structuredContent` against `outputSchema` whenever it
    // is present -- errors included. A separate error shape would be rejected inside
    // the client and section 14.4's actionable error would never reach the agent.
    const result = toolFailure({
      code: "sync.missing_required_field",
      message: "Give the day.",
      retryable: false,
      field: "startedOn",
    });
    expect(schema.safeParse(result.structuredContent).success).toBe(true);
    expect(result.isError).toBe(true);
  });
});

describe("the list cursor", () => {
  test("round-trips a key without revealing it", () => {
    const cursor = encodeListCursor("2026-08-25");
    expect(cursor).not.toContain("2026");
    expect(decodeListCursor(cursor)).toBe("2026-08-25");
  });

  test("refuses anything it did not issue", () => {
    for (const bad of ["", "not-base64!", btoa("{}").replaceAll("=", ""), "eyJ2IjoyfQ"]) {
      expect(() => decodeListCursor(bad)).toThrow(InvalidCursorError);
    }
  });
});

describe("agent identity", () => {
  test("reads the subject, the client and the granted scopes", () => {
    const identity = readAgentIdentity({
      sub: "user-1",
      client_id: "agent-1",
      scope: "weight:read activity:write",
      jti: "token-1",
    });
    expect(identity.userId).toBe("user-1");
    expect(identity.clientId).toBe("agent-1");
    expect([...identity.scopes].sort()).toEqual(["activity:write", "weight:read"]);
    expect(identity.tokenId).toBe("token-1");
  });

  test("accepts `azp` as well as `client_id`", () => {
    expect(readAgentIdentity({ sub: "u", azp: "agent-2" }).clientId).toBe("agent-2");
  });

  test("drops a scope this build does not implement", () => {
    // A name the server does not know cannot widen what a tool believes it holds.
    const identity = readAgentIdentity({ sub: "u", azp: "a", scope: "weight:read admin:all" });
    expect([...identity.scopes]).toEqual(["weight:read"]);
  });

  test("refuses a token with no subject or no client", () => {
    expect(() => readAgentIdentity({ client_id: "a" })).toThrow(IdentityError);
    expect(() => readAgentIdentity({ sub: "u" })).toThrow(IdentityError);
  });
});

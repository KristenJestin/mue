import { describe, expect, test } from "bun:test";
import { createActivitySessionService, isUsingProvisionalActivityWrite } from "./domain-bridge";

describe("the domain bridge", () => {
  test("resolves an activity write service", () => {
    expect(typeof createActivitySessionService()).toBe("function");
  });

  /**
   * A deliberate tripwire, and the only honest way to keep PRD section 20.2 -- one rule,
   * one implementation, called by the routes, the server functions and the MCP tools
   * alike -- from quietly having two.
   *
   * While `@mue/domain` exports no activity write, `./provisional-activity-write.ts`
   * stands in and this passes. The day the sync engine lands one, the bridge switches
   * to it on its own and this test fails, which is the reminder to delete the
   * scaffolding file and this test with it.
   */
  test("still falls back to the scaffolding, because @mue/domain has no activity write yet", () => {
    expect(isUsingProvisionalActivityWrite()).toBe(true);
  });
});

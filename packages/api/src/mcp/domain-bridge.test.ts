import { describe, expect, test } from "bun:test";
import { createActivitySessionService, isUsingProvisionalActivityWrite } from "./domain-bridge";

describe("the domain bridge", () => {
  test("resolves an activity write service", () => {
    expect(typeof createActivitySessionService()).toBe("function");
  });

  /**
   * The tripwire, now green from the other side.
   *
   * While `@mue/domain` exported no activity write, `./provisional-activity-write.ts` stood in
   * and this test asserted `true` — the reminder that PRD section 20.2 was being kept only by
   * convention, with two implementations of one rule. The sync engine landed
   * `createActivitySession`, the bridge switched to it, and the scaffolding file is deleted.
   *
   * The assertion is inverted rather than removed, because it is the only thing that would notice
   * a fallback being reintroduced.
   */
  test("no longer falls back to scaffolding: the rule lives in @mue/domain", () => {
    expect(isUsingProvisionalActivityWrite()).toBe(false);
  });
});

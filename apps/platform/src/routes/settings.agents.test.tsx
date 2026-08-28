import { describe, expect, test } from "bun:test";
import {
  MAX_PAIRING_MINUTES,
  PAIRING_POLL_INTERVAL_MS,
  PAIRING_TRUST_MS,
  clampMinutes,
  formatRemaining,
  knowledgeFromAnswer,
  knowledgeFromFailure,
  pairingDisplay,
  sortAgents,
  type AgentRow,
} from "./settings.agents";

const T0 = Date.parse("2026-08-28T12:00:00.000Z");

function agent(overrides: Partial<AgentRow> & Pick<AgentRow, "clientId">): AgentRow {
  return {
    name: null,
    scopes: [],
    revoked: false,
    discovered: false,
    registeredAt: null,
    lastUsedAt: null,
    ...overrides,
  };
}

describe("what the browser is allowed to claim about the pairing window", () => {
  test("an open answer counts down from the instant the server named", () => {
    const knowledge = knowledgeFromAnswer(
      { open: true, until: new Date(T0 + 600_000).toISOString() },
      T0,
    );
    expect(pairingDisplay(knowledge, T0)).toEqual({ kind: "open", remainingMs: 600_000 });
    expect(pairingDisplay(knowledge, T0 + 1_000)).toEqual({ kind: "open", remainingMs: 599_000 });
  });

  test("a later answer of closed replaces a window that still had time left", () => {
    // This is the restart. The window lives only in the server process, so a
    // restart closes it while the browser still holds an `until` in the future.
    const open = knowledgeFromAnswer(
      { open: true, until: new Date(T0 + 600_000).toISOString() },
      T0,
    );
    expect(pairingDisplay(open, T0 + 5_000)).toEqual({ kind: "open", remainingMs: 595_000 });

    const closed = knowledgeFromAnswer({ open: false, until: null }, T0 + 5_000);
    expect(pairingDisplay(closed, T0 + 5_000)).toEqual({ kind: "closed" });
  });

  test("a poll that never came back stops the countdown instead of continuing it", () => {
    // The other half of the restart: the server is down, so nothing answers. A timer
    // that kept ticking here would be telling the owner a window is open that no
    // process is holding.
    expect(pairingDisplay(knowledgeFromFailure(T0), T0 + 1_000)).toEqual({ kind: "unreachable" });
  });

  test("a confirmation the poll has stopped refreshing is no longer trusted", () => {
    const knowledge = knowledgeFromAnswer(
      { open: true, until: new Date(T0 + 3_600_000).toISOString() },
      T0,
    );
    expect(pairingDisplay(knowledge, T0 + PAIRING_TRUST_MS - 1)).toMatchObject({ kind: "open" });
    expect(pairingDisplay(knowledge, T0 + PAIRING_TRUST_MS + 1)).toEqual({ kind: "stale" });
  });

  test("the trust horizon leaves room for a poll to be missed, not for it to be forgotten", () => {
    expect(PAIRING_TRUST_MS).toBeGreaterThan(PAIRING_POLL_INTERVAL_MS);
    expect(PAIRING_TRUST_MS).toBeLessThanOrEqual(6 * PAIRING_POLL_INTERVAL_MS);
  });

  test("reaching the server's own deadline is closed, not a negative countdown", () => {
    const knowledge = knowledgeFromAnswer(
      { open: true, until: new Date(T0 + 2_000).toISOString() },
      T0,
    );
    expect(pairingDisplay(knowledge, T0 + 2_000)).toEqual({ kind: "closed" });
    expect(pairingDisplay(knowledge, T0 + 9_000)).toEqual({ kind: "closed" });
  });

  test("before the first answer it says it is still asking", () => {
    expect(pairingDisplay({ kind: "asking" }, T0)).toEqual({ kind: "asking" });
  });

  test("an answer that is open but names no instant is not an open window", () => {
    expect(pairingDisplay(knowledgeFromAnswer({ open: true, until: null }, T0), T0)).toEqual({
      kind: "closed",
    });
  });
});

describe("the remaining time, as a person reads it", () => {
  test("is minutes and seconds, zero padded", () => {
    expect(formatRemaining(600_000)).toBe("10:00");
    expect(formatRemaining(65_000)).toBe("1:05");
    expect(formatRemaining(9_000)).toBe("0:09");
  });

  test("rounds up, so it never shows 0:00 while the window is still open", () => {
    expect(formatRemaining(1)).toBe("0:01");
    expect(formatRemaining(59_001)).toBe("1:00");
  });

  test("never goes below zero", () => {
    expect(formatRemaining(0)).toBe("0:00");
    expect(formatRemaining(-5_000)).toBe("0:00");
  });
});

describe("the minutes the owner may ask for", () => {
  test("stay inside what the server would clamp them to anyway", () => {
    expect(clampMinutes(10)).toBe(10);
    expect(clampMinutes(0)).toBe(1);
    expect(clampMinutes(-3)).toBe(1);
    expect(clampMinutes(500)).toBe(MAX_PAIRING_MINUTES);
    expect(clampMinutes(12.7)).toBe(12);
    expect(clampMinutes(Number.NaN)).toBe(1);
  });
});

describe("the order the agents are shown in", () => {
  test("puts the live ones first and the newest registration at the top", () => {
    const rows: AgentRow[] = [
      agent({ clientId: "old", registeredAt: "2026-01-01T00:00:00.000Z" }),
      agent({ clientId: "revoked-new", revoked: true, registeredAt: "2026-08-01T00:00:00.000Z" }),
      agent({ clientId: "new", registeredAt: "2026-07-01T00:00:00.000Z" }),
    ];
    expect(sortAgents(rows).map((row) => row.clientId)).toEqual(["new", "old", "revoked-new"]);
  });

  test("keeps an agent whose registration date is unknown, at the end of its group", () => {
    const rows: AgentRow[] = [
      agent({ clientId: "undated" }),
      agent({ clientId: "dated", registeredAt: "2026-01-01T00:00:00.000Z" }),
    ];
    expect(sortAgents(rows).map((row) => row.clientId)).toEqual(["dated", "undated"]);
  });

  test("does not mutate what it was given", () => {
    const rows: AgentRow[] = [agent({ clientId: "b", revoked: true }), agent({ clientId: "a" })];
    sortAgents(rows);
    expect(rows.map((row) => row.clientId)).toEqual(["b", "a"]);
  });
});

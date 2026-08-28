import { describe, expect, test } from "bun:test";
import {
  birthDay,
  CLOCK_SKEW_TOLERANCE_DAYS,
  earliestBirthDay,
  earliestPlannableDay,
  furthestPlannableDay,
  latestRecordedDay,
  LIFETIME_MAX_YEARS,
  lifetimeFloor,
  pastEventDay,
  planningWindow,
  shiftDays,
  today,
} from "./date-policy";
import { MEAL_PLAN_MAX_DAYS_AHEAD } from "./meal-plan";

/**
 * The three rules, judged against a frozen clock.
 *
 * Every case injects `now`, which is the point: the rules read a clock, so a test that let them
 * read the real one would pass or fail depending on the hour it ran and would say nothing about
 * the boundary it claims to pin. The frozen instant below is deliberately late in the UTC day —
 * 22:00 — because that is when a reference day computed in the *process's* timezone and one
 * computed in UTC diverge, and this module promises UTC.
 */
const NOW = new Date("2026-08-28T22:00:00.000Z");

describe("the reference day", () => {
  test("is the UTC day, not the day the host happens to be living in", () => {
    expect(today(NOW)).toBe("2026-08-28");
    // An instant that is already tomorrow anywhere east of UTC+2 is still today in UTC, which
    // is what makes "one day of tolerance" a derivation rather than a guess.
    expect(today(new Date("2026-08-28T23:59:59.999Z"))).toBe("2026-08-28");
    expect(today(new Date("2026-08-29T00:00:00.000Z"))).toBe("2026-08-29");
  });

  test("shifts by whole days across a month and a leap day", () => {
    expect(shiftDays("2026-08-31", 1)).toBe("2026-09-01");
    expect(shiftDays("2026-01-01", -1)).toBe("2025-12-31");
    expect(shiftDays("2028-02-28", 1)).toBe("2028-02-29");
  });
});

describe("pastEventDay — a day something happened", () => {
  test("admits today, the past, and exactly one day of clock skew", () => {
    expect(pastEventDay("date", "2026-08-28", { now: NOW })).toBeUndefined();
    expect(pastEventDay("date", "1990-01-01", { now: NOW })).toBeUndefined();
    // The tolerance: a device at UTC+14 is already on 2026-08-29 while UTC is not.
    expect(pastEventDay("date", "2026-08-29", { now: NOW })).toBeUndefined();
    expect(latestRecordedDay(NOW)).toBe("2026-08-29");
  });

  test("refuses the first day past the tolerance, and F-02's own date", () => {
    const beyond = pastEventDay("startedOn", "2026-08-30", { now: NOW });
    expect(beyond?.rule).toBe("pastEventDay");
    expect(beyond?.field).toBe("startedOn");

    // The case as it was reported: `mue.create_activity` accepted this.
    const reported = pastEventDay("startedOn", "2099-12-01", { now: NOW });
    expect(reported).toBeDefined();
    expect(reported?.rule).toBe("pastEventDay");
  });

  test("the tolerance is one day, and is the whole of the tolerance", () => {
    // Pinned rather than inferred: the constant is a derivation from the civil-offset range
    // (UTC-12 to UTC+14 puts a correct device's calendar at most one day either side of UTC),
    // so a change to it is a change of policy and has to be made deliberately.
    expect(CLOCK_SKEW_TOLERANCE_DAYS).toBe(1);
    expect(latestRecordedDay(NOW)).toBe(shiftDays(today(NOW), CLOCK_SKEW_TOLERANCE_DAYS));
  });

  test("names the field and the bound, and never the value it was given", () => {
    const violation = pastEventDay("payload.consumedOn", "2099-12-01", { now: NOW });
    expect(violation?.message).toContain("payload.consumedOn");
    expect(violation?.message).toContain("2026-08-29");
    // `MueError.message` is documented "safe to log: no personal data". The day a person ate
    // or trained on is exactly that, and the caller already holds what it sent.
    expect(violation?.message).not.toContain("2099-12-01");
  });

  test("appends a caller's hint so a tool can say what to use instead", () => {
    const violation = pastEventDay("consumedOn", "2099-01-01", {
      now: NOW,
      hint: "Use `mue.plan_meal`.",
    });
    expect(violation?.message).toEndWith("Use `mue.plan_meal`.");
  });
});

describe("planningWindow — a day something is proposed for", () => {
  test("runs from one day back to sixty days ahead", () => {
    expect(earliestPlannableDay(NOW)).toBe("2026-08-27");
    expect(furthestPlannableDay(NOW)).toBe(shiftDays("2026-08-28", MEAL_PLAN_MAX_DAYS_AHEAD));

    expect(planningWindow("plannedOn", "2026-08-28", { now: NOW })).toBeUndefined();
    // The lower bound carries the tolerance for the mirror-image reason the upper one does.
    expect(planningWindow("plannedOn", "2026-08-27", { now: NOW })).toBeUndefined();
    expect(planningWindow("plannedOn", furthestPlannableDay(NOW), { now: NOW })).toBeUndefined();
  });

  test("refuses either side, naming the field and which bound was crossed", () => {
    const early = planningWindow("plannedOn", "2026-08-26", { now: NOW });
    expect(early?.rule).toBe("planningWindow");
    expect(early?.field).toBe("plannedOn");
    expect(early?.message).toContain("2026-08-27");

    const late = planningWindow("plannedOn", shiftDays(furthestPlannableDay(NOW), 1), { now: NOW });
    expect(late?.rule).toBe("planningWindow");
    expect(late?.message).toContain(String(MEAL_PLAN_MAX_DAYS_AHEAD));
  });
});

describe("lifetimeFloor and birthDay — a day within a human lifetime", () => {
  test("reaches back exactly 120 years", () => {
    expect(LIFETIME_MAX_YEARS).toBe(120);
    expect(earliestBirthDay(NOW)).toBe("1906-08-28");
    expect(lifetimeFloor("birthDate", "1906-08-28", { now: NOW })).toBeUndefined();

    const ancient = lifetimeFloor("birthDate", "1906-08-27", { now: NOW });
    expect(ancient?.rule).toBe("lifetimeFloor");
    expect(ancient?.field).toBe("birthDate");
    expect(ancient?.message).toContain("120");
  });

  test("birthDay reports a future date as a future date, not as a lifetime that has not begun", () => {
    // PRD section 11.2 states the two halves in this order, and an agent that gave next year
    // needs to be told it is in the future rather than that it is too long ago.
    const future = birthDay("birthDate", "2027-01-01", { now: NOW });
    expect(future?.rule).toBe("pastEventDay");

    expect(birthDay("birthDate", "1998-11-18", { now: NOW })).toBeUndefined();
    expect(birthDay("birthDate", "1900-01-01", { now: NOW })?.rule).toBe("lifetimeFloor");
  });
});

describe("what the rules are not", () => {
  test("pastEventDay is stable under replay, which is why push may apply it", () => {
    // A day that was inside the bound when a row was written is still inside it a week later,
    // because time moves one way. This is the property that lets `validateMutation` enforce it
    // on a pushed mutation without ever stranding a row a phone already stored.
    const written = "2026-08-29";
    expect(pastEventDay("startedOn", written, { now: NOW })).toBeUndefined();
    const laterStill = new Date("2026-09-04T22:00:00.000Z");
    expect(pastEventDay("startedOn", written, { now: laterStill })).toBeUndefined();
  });

  test("planningWindow is not, which is why push may not", () => {
    // The same proposal, journalled for tomorrow and pushed three days late. Enforcing the
    // window at push would refuse it -- permanently, since `push` replays stored rejections --
    // so the window stays at the point a proposal is made.
    const proposal = "2026-08-29";
    expect(planningWindow("plannedOn", proposal, { now: NOW })).toBeUndefined();
    const threeDaysLate = new Date("2026-09-01T22:00:00.000Z");
    expect(planningWindow("plannedOn", proposal, { now: threeDaysLate })).toBeDefined();
  });
});

/**
 * Where a business date is allowed to fall, as a small set of named rules applied from one
 * place.
 *
 * ## Why this module exists
 *
 * F-02: `plan_meal` refused `2099-12-01`, `create_food_log` refused a meal eaten in the
 * future, and `create_activity` accepted a session several decades ahead. Three tools, three
 * answers, because each one carried its own copy of the question — including two byte-identical
 * `serverLocalDate()` helpers whose own comments pointed at each other. A rule that lives in
 * every caller is a rule that only ever gets fixed in the caller someone happened to read.
 *
 * ## It is deliberately not one rule
 *
 * The dates Mue carries are not the same kind of thing, and flattening them would be a
 * different bug rather than a fix:
 *
 *  - a finished session, a consumed meal and a weighing are **records of things that happened**,
 *    so they belong in the past ([pastEventDay]);
 *  - a planned meal is **deliberately ahead**, and PRD_FOOD 15 already bounds it to sixty days
 *    ([planningWindow]);
 *  - a date of birth is in the past **by decades**, and PRD section 11.2 bounds how many
 *    ([lifetimeFloor]).
 *
 * So there are three rules, each named, and every write path states which one it applies.
 *
 * ## The tolerance, and why it is exactly one day
 *
 * Every date these rules judge is a **zoneless local calendar date** — PRD section 11.1 and
 * PRD_ACTIVITIES 8.2 both store `YYYY-MM-DD` with no instant, no epoch and no zone, precisely so
 * that a change of timezone can never shift a row by a day. The consequence is that the server
 * cannot know *which* day the author meant: it holds a calendar date computed on a device, and
 * it must judge it against a calendar date of its own.
 *
 * Two correct clocks therefore disagree, and by a bounded amount. Civil offsets run from UTC−12
 * to UTC+14, so a device's own calendar date is at most **one day ahead of, or one day behind,
 * the UTC date** — a phone in Kiritimati at 00:30 local is already on a day UTC has not reached.
 * That is the whole of the legitimate disagreement, and one day is what covers it. It is a
 * derived number, not a chosen one, which is also why the reference day is UTC ([today]) rather
 * than the server process's timezone: pinning it to UTC is what makes "one day" provably
 * sufficient instead of a function of how the host happens to be configured.
 *
 * Anything past that is a clock that is *wrong* rather than merely elsewhere, and no tolerance
 * rescues data whose date is wrong. Widening the window buys nothing against it and costs the
 * rule its meaning: at thirty days, "a session that happened" would admit one next month. The
 * mistakes actually seen — an agent's `2099-12-01`, a mistyped year, a month off by one — are
 * all more than a day out, so one day rejects every one of them while rejecting no device that
 * is merely on the other side of the planet.
 *
 * ## Where these rules may and may not be applied
 *
 * They are **not** schema refinements, and must not become any. `pull` re-parses every
 * journalled change through `syncChangeSchema`, so a clock-relative bound inside a payload
 * schema would one day make a change that was valid when it was accepted fail to parse and stop
 * a client's cursor dead on data it already holds. `health-profile.ts` and `meal-plan.ts` both
 * argue this at length; this module is what lets them keep their absolute schemas while the
 * clock-relative half is enforced where a value is *authored*.
 *
 * Two authoring paths exist and both are covered: the MCP tools, which check before submitting
 * so the agent is told in its own vocabulary, and `validateMutation`, through which every
 * pushed mutation and every MCP write passes. [planningWindow] is the one exception and it is
 * deliberate — see the note on that function.
 */

import { MEAL_PLAN_MAX_DAYS_AHEAD } from "./meal-plan";

/**
 * The tolerance, in whole calendar days, between a date computed on a device and the same day
 * computed on the server. One, and the module docstring derives it: it is the width of the
 * civil-offset range relative to UTC, not a guess.
 */
export const CLOCK_SKEW_TOLERANCE_DAYS = 1;

/**
 * How far back a date of birth may reach, from PRD section 11.2 — *"pas antérieure de plus de
 * 120 ans"* — and from Android's `MueValidation.validateBirthDate`, which words the same bound
 * against the phone's clock.
 */
export const LIFETIME_MAX_YEARS = 120;

/** The rules, named. A violation carries the name so a caller can log which one refused. */
export const DATE_RULES = ["pastEventDay", "planningWindow", "lifetimeFloor"] as const;

export type DateRuleName = (typeof DATE_RULES)[number];

export interface DateRuleViolation {
  /** Which rule refused, for logs and tests. Never shown to a person. */
  readonly rule: DateRuleName;
  /** The offending field, in the vocabulary of whichever path is asking. */
  readonly field: string;
  /**
   * English, actionable, and safe to log.
   *
   * It names the field and the bound and stops there. The received value is deliberately
   * absent: `MueError.message` is documented *"safe to log: no personal data"*, and the day a
   * person trained or ate on is health data — while the caller that sent it already has it.
   * What a caller cannot reconstruct is the bound, so the bound is what the message carries.
   */
  readonly message: string;
}

/**
 * The server's reference calendar day, in UTC.
 *
 * UTC and not the process timezone, for the reason the module docstring gives: the tolerance is
 * derived from the civil-offset range measured against UTC, so anchoring the comparison
 * anywhere else would make the derivation false. It also makes the rules reproducible on a test
 * host in any zone.
 */
export function today(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}

/** A calendar day shifted by whole days, staying in UTC so no zone can move it. */
export function shiftDays(day: string, days: number): string {
  const shifted = new Date(`${day}T00:00:00.000Z`);
  shifted.setUTCDate(shifted.getUTCDate() + days);
  return shifted.toISOString().slice(0, 10);
}

/** A calendar day shifted by whole years, for [lifetimeFloor]. */
function shiftYears(day: string, years: number): string {
  const shifted = new Date(`${day}T00:00:00.000Z`);
  shifted.setUTCFullYear(shifted.getUTCFullYear() + years);
  return shifted.toISOString().slice(0, 10);
}

/** The latest day a record of something that happened may carry. */
export function latestRecordedDay(now: Date = new Date()): string {
  return shiftDays(today(now), CLOCK_SKEW_TOLERANCE_DAYS);
}

/** The furthest day a proposal may be made for: today plus PRD_FOOD 15's sixty. */
export function furthestPlannableDay(now: Date = new Date()): string {
  return shiftDays(today(now), MEAL_PLAN_MAX_DAYS_AHEAD);
}

/** The earliest day a proposal may be made for, tolerance included. */
export function earliestPlannableDay(now: Date = new Date()): string {
  return shiftDays(today(now), -CLOCK_SKEW_TOLERANCE_DAYS);
}

/** The earliest day of birth a living author can carry. */
export function earliestBirthDay(now: Date = new Date()): string {
  return shiftYears(today(now), -LIFETIME_MAX_YEARS);
}

interface RuleOptions {
  /** Injected by tests; production callers read the wall clock. */
  readonly now?: Date;
  /**
   * One sentence of path-specific guidance appended to the message, for a tool that can say
   * something more useful than the rule alone — *"to plan a meal ahead, use `mue.plan_meal`"*.
   */
  readonly hint?: string;
}

function violation(
  rule: DateRuleName,
  field: string,
  message: string,
  hint: string | undefined,
): DateRuleViolation {
  return { rule, field, message: hint === undefined ? message : `${message} ${hint}` };
}

/**
 * **A day something happened.** It may be as far back as the author likes and no further ahead
 * than today plus the skew tolerance.
 *
 * Applies to a weighing's `date` (PRD section 11.1, BR-009: *"Aucune mesure ne peut porter une
 * date postérieure à aujourd'hui"*), an activity session's `startedOn` (PRD_ACTIVITIES
 * FR-ACTIVITY-005: *"Interdire les dates futures"*), a journal line's `consumedOn` (PRD_FOOD 15:
 * *"Aujourd'hui ou dans le passé, jamais dans le futur"*) and a `birthDate`, which is also a day
 * something happened and additionally answers to [lifetimeFloor].
 *
 * The bound is **stable under replay**: a date that was not in the future when it was written is
 * still not in the future when it is pushed a week later, because time only moves one way. That
 * is what makes this rule safe on the sync push path, where [planningWindow] is not.
 */
export function pastEventDay(
  field: string,
  value: string,
  options: RuleOptions = {},
): DateRuleViolation | undefined {
  const latest = latestRecordedDay(options.now);
  if (value <= latest) return undefined;
  return violation(
    "pastEventDay",
    field,
    `\`${field}\` is the day something happened, so it cannot be later than ${latest}. ` +
      `That is the server's day in UTC plus ${CLOCK_SKEW_TOLERANCE_DAYS} day, which is all the ` +
      `difference there can be between a device's calendar and the server's.`,
    options.hint,
  );
}

/**
 * **A day something is proposed for.** From today back by the skew tolerance to today plus
 * [MEAL_PLAN_MAX_DAYS_AHEAD], per PRD_FOOD 15: *"Aujourd'hui ou dans le futur, dans les 60
 * jours"*.
 *
 * The lower bound carries the tolerance for the mirror-image reason the upper one does: a device
 * at UTC−12 is still on a day the server has already left, and refusing it to plan its own
 * dinner would be F-02 in the other direction.
 *
 * **This rule is deliberately not applied on the sync push path**, and that is not an oversight.
 * It is the only one of the three that is *unstable under replay*: a proposal written for
 * tomorrow, journalled on a phone that then spends three days offline, arrives with a
 * `plannedOn` that is now in the past. Enforcing the window at push would refuse it — and
 * because `push` records rejections under their `mutationId` and replays them verbatim, it would
 * refuse it permanently, stranding a row the phone has already stored. `meal-plan.ts` states the
 * same conclusion about the payload schema. So the window is checked where a proposal is *made*,
 * which is `mue.plan_meal`, and nowhere else.
 */
export function planningWindow(
  field: string,
  value: string,
  options: RuleOptions = {},
): DateRuleViolation | undefined {
  const earliest = earliestPlannableDay(options.now);
  if (value < earliest) {
    return violation(
      "planningWindow",
      field,
      `\`${field}\` is the day a meal is proposed for, so it cannot be earlier than ${earliest}.`,
      options.hint,
    );
  }
  const furthest = furthestPlannableDay(options.now);
  if (value > furthest) {
    return violation(
      "planningWindow",
      field,
      `A meal can be planned at most ${MEAL_PLAN_MAX_DAYS_AHEAD} days ahead, so \`${field}\` ` +
        `cannot be later than ${furthest}.`,
      options.hint,
    );
  }
  return undefined;
}

/**
 * **A day within a human lifetime.** No earlier than [LIFETIME_MAX_YEARS] before today.
 *
 * Composed with [pastEventDay] rather than repeating its upper bound, because a birth date is a
 * day something happened and the two halves of PRD section 11.2 — *"Pas dans le futur, pas
 * antérieure de plus de 120 ans"* — are exactly those two rules.
 *
 * Its floor decays with the calendar, so it is applied only where a profile is authored, never
 * to a stored payload: `birthDateSchema` keeps the absolute `1900-2099` pattern that makes a
 * journalled profile parseable for ever.
 */
export function lifetimeFloor(
  field: string,
  value: string,
  options: RuleOptions = {},
): DateRuleViolation | undefined {
  const earliest = earliestBirthDay(options.now);
  if (value >= earliest) return undefined;
  return violation(
    "lifetimeFloor",
    field,
    `\`${field}\` cannot be more than ${LIFETIME_MAX_YEARS} years ago, so it cannot be earlier ` +
      `than ${earliest}.`,
    options.hint,
  );
}

/**
 * The birth-date rule as one call: a day that happened, within a lifetime.
 *
 * Both halves in the order PRD section 11.2 states them, so a future date is reported as a
 * future date rather than as a lifetime that has not started.
 */
export function birthDay(
  field: string,
  value: string,
  options: RuleOptions = {},
): DateRuleViolation | undefined {
  return pastEventDay(field, value, options) ?? lifetimeFloor(field, value, options);
}

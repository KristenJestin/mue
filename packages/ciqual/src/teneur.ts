// Reading one `<teneur>` out of Ciqual's composition table.
//
// Three of Ciqual's encodings are not numbers, and each one of them would become a
// fabricated value under a plain `parseFloat`:
//
//   `-`        the constituent was not determined for this food. 83 246 of the
//              257 816 rows in Ciqual 2025 look like this. `parseFloat("-")` is
//              `NaN`, but `Number("-")` is `NaN` too and `parseFloat("- ")` in a
//              pipeline that falls back to `0` publishes a zero calorie count.
//   `traces`   present below the quantification limit.
//   `< 0,01`   present below a stated limit.
//
// PRD_FOOD 13.1 forbids reading any of them as zero: `null` means unknown, `0`
// means a measured zero, and the two must not be confused. So this module never
// returns a number for them — it returns *which* of them it was, and the caller
// decides. The traces cases are counted into the build report rather than dropped
// silently, because mapping them to `0` would claim knowledge the source refuses
// to state.
//
// The numbers themselves carry French decimal commas (`59,7`) and, on the wider
// values, group separators that may be a plain space, a no-break space or a narrow
// no-break space. `parseFloat` is locale-insensitive in the worst way here: it reads
// `59,7` as `59`, losing the decimals without failing. Hence an explicit grammar.

/** What a `<teneur>` cell actually said. */
export type TeneurKind =
  /** A number, which may legitimately be zero. */
  | "value"
  /** `-`, or an absent element: not determined. Unknown, never zero. */
  | "notDetermined"
  /** `traces`: present, unquantified. Unknown, never zero. */
  | "traces"
  /** `< x`: present below a stated limit. Unknown, never zero. */
  | "lessThan";

export interface Teneur {
  readonly kind: TeneurKind;
  /** The cell as it appeared, trimmed; kept so a report can quote the source. */
  readonly raw: string;
  /**
   * A canonical decimal *string* — never a float — for `kind === "value"`, and the
   * stated limit for `kind === "lessThan"`. Keeping it textual is what lets
   * {@link scaleDecimalToInteger} convert exactly.
   */
  readonly decimal: string | null;
}

export class TeneurParseError extends Error {
  constructor(readonly cell: string) {
    super(`Unrecognised Ciqual teneur cell: ${JSON.stringify(cell)}`);
    this.name = "TeneurParseError";
  }
}

const NUMBER = /^[+-]?\d+(?:[.,]\d+)?$/;

/** Ciqual separates thousands with any of three space characters. */
function stripSpaces(text: string): string {
  return text.replace(/[\s\u00a0\u202f]/g, "");
}

function toCanonicalDecimal(text: string): string | null {
  const compact = stripSpaces(text);
  if (!NUMBER.test(compact)) return null;
  return compact.replace(",", ".").replace(/^\+/, "");
}

/**
 * Total: every Ciqual cell is one of the four kinds, and anything else throws rather
 * than degrading to a null the report would count as an honest unknown. A source
 * whose grammar changed is a build to stop, not a catalogue to ship.
 */
export function parseTeneur(cell: string | null | undefined): Teneur {
  const raw = (cell ?? "").trim();
  if (raw === "" || raw === "-") return { kind: "notDetermined", raw, decimal: null };
  if (/^traces$/i.test(raw)) return { kind: "traces", raw, decimal: null };

  if (raw.startsWith("<")) {
    const limit = toCanonicalDecimal(raw.slice(1));
    if (limit === null) throw new TeneurParseError(raw);
    return { kind: "lessThan", raw, decimal: limit };
  }

  const decimal = toCanonicalDecimal(raw);
  if (decimal === null) throw new TeneurParseError(raw);
  return { kind: "value", raw, decimal };
}

/**
 * `59.7` at three places is `59700`, computed by moving the decimal point across a
 * string rather than by multiplying a float.
 *
 * `parseFloat("0.29") * 1000` is `289.99999999999994`; rounding rescues that one and
 * not every one. PRD_FOOD 8.6 stores thousandths precisely so the phone parses no
 * float at all, and a generator that reintroduces one at the last step gives that up.
 * Half-up on the first discarded digit, which is what the source's own rounding does.
 */
export function scaleDecimalToInteger(decimal: string, places: number): number {
  const negative = decimal.startsWith("-");
  const unsigned = negative ? decimal.slice(1) : decimal;
  const dot = unsigned.indexOf(".");
  const whole = dot === -1 ? unsigned : unsigned.slice(0, dot);
  const fraction = dot === -1 ? "" : unsigned.slice(dot + 1);

  const padded = `${fraction}${"0".repeat(places + 1)}`;
  const kept = padded.slice(0, places);
  const nextDigit = padded.charCodeAt(places) - 48;

  const digits = `${whole}${kept}`.replace(/^0+(?=\d)/, "");
  const scaled = Number(digits === "" ? "0" : digits) + (nextDigit >= 5 ? 1 : 0);
  return negative ? -scaled : scaled;
}

/**
 * The one conversion the catalogue performs: a cell to a canonical integer, or `null`
 * for all three of Ciqual's unknowns.
 */
export function teneurToThousandths(teneur: Teneur): number | null {
  if (teneur.kind !== "value" || teneur.decimal === null) return null;
  return scaleDecimalToInteger(teneur.decimal, 3);
}

/** The same value as a float, for the ratio arithmetic only — never for an emitted field. */
export function teneurToNumber(teneur: Teneur): number | null {
  if (teneur.kind !== "value" || teneur.decimal === null) return null;
  return Number(teneur.decimal);
}

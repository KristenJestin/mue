import { describe, expect, test } from "bun:test";

import {
  parseTeneur,
  scaleDecimalToInteger,
  TeneurParseError,
  teneurToNumber,
  teneurToThousandths,
} from "./teneur";

describe("parseTeneur", () => {
  test("reads a French decimal comma without losing the decimals", () => {
    // The failure this guards against is silent, not loud: `parseFloat("59,7")` returns
    // 59, which is a plausible number that happens to be wrong by 1.2 %.
    expect(parseTeneur("59,7")).toEqual({ kind: "value", raw: "59,7", decimal: "59.7" });
    expect(parseTeneur(" 1140 ")).toEqual({ kind: "value", raw: "1140", decimal: "1140" });
    expect(parseTeneur("0,0002").decimal).toBe("0.0002");
  });

  test('"-" is not determined, and never zero', () => {
    // 83 246 of Ciqual 2025's cells look like this. PRD_FOOD 13.1: null is unknown, 0 is
    // a measured zero, and no conversion between them is allowed.
    const teneur = parseTeneur("-");
    expect(teneur.kind).toBe("notDetermined");
    expect(teneurToThousandths(teneur)).toBeNull();
  });

  test('"traces" is unknown, and never zero', () => {
    const teneur = parseTeneur("traces");
    expect(teneur.kind).toBe("traces");
    expect(teneurToThousandths(teneur)).toBeNull();
  });

  test('"< x" is unknown, and never zero, but the stated limit is kept', () => {
    const teneur = parseTeneur("< 0,01");
    expect(teneur.kind).toBe("lessThan");
    expect(teneur.decimal).toBe("0.01");
    // Mapping a trace to 0 would claim knowledge the source explicitly refuses to state.
    expect(teneurToThousandths(teneur)).toBeNull();
    expect(teneurToNumber(teneur)).toBeNull();
  });

  test("a measured zero stays a zero", () => {
    const teneur = parseTeneur("0");
    expect(teneur.kind).toBe("value");
    expect(teneurToThousandths(teneur)).toBe(0);
  });

  test("an empty or absent cell is not determined", () => {
    expect(parseTeneur("").kind).toBe("notDetermined");
    expect(parseTeneur(null).kind).toBe("notDetermined");
    expect(parseTeneur(undefined).kind).toBe("notDetermined");
  });

  test("group separators, including the no-break kinds Ciqual uses", () => {
    expect(parseTeneur("1 140").decimal).toBe("1140");
    expect(parseTeneur("1 140").decimal).toBe("1140");
    expect(parseTeneur("1 140,5").decimal).toBe("1140.5");
  });

  test("an unrecognised cell stops the build instead of becoming a null", () => {
    // A null here would be counted in the report as an honest unknown, which is exactly
    // how a changed source grammar would hide.
    expect(() => parseTeneur("n.d.")).toThrow(TeneurParseError);
    expect(() => parseTeneur("< beaucoup")).toThrow(TeneurParseError);
  });
});

describe("scaleDecimalToInteger", () => {
  test("moves the decimal point rather than multiplying a float", () => {
    // `parseFloat("0.29") * 1000` is 289.99999999999994. Rounding rescues that one case
    // and not the general one, and PRD_FOOD 8.6 stores thousandths precisely so that the
    // phone parses no float at all.
    expect(scaleDecimalToInteger("0.29", 3)).toBe(290);
    expect(scaleDecimalToInteger("59.7", 3)).toBe(59_700);
    expect(scaleDecimalToInteger("1140", 3)).toBe(1_140_000);
    expect(scaleDecimalToInteger("7.02", 3)).toBe(7_020);
    expect(scaleDecimalToInteger("0", 3)).toBe(0);
  });

  test("rounds half up on the first discarded digit", () => {
    expect(scaleDecimalToInteger("0.0005", 3)).toBe(1);
    expect(scaleDecimalToInteger("0.0004", 3)).toBe(0);
    expect(scaleDecimalToInteger("0.00049", 3)).toBe(0);
  });

  test("never produces a float artefact for any Ciqual-shaped value", () => {
    for (let whole = 0; whole <= 100; whole += 1) {
      for (let hundredths = 0; hundredths < 100; hundredths += 1) {
        const decimal = `${whole}.${String(hundredths).padStart(2, "0")}`;
        const scaled = scaleDecimalToInteger(decimal, 3);
        expect(Number.isInteger(scaled)).toBe(true);
        expect(scaled).toBe(whole * 1_000 + hundredths * 10);
      }
    }
  });
});

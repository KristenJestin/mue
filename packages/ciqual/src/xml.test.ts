import { describe, expect, test } from "bun:test";

import { fixture } from "./fixtures";
import { parseTeneur } from "./teneur";
import { decodeEntities, readRecords, requireField } from "./xml";

describe("readRecords", () => {
  test("reads a self-closing element as null, not as an empty string", () => {
    // `<alim_nom_sci missing=" " />` is how Ciqual writes "no value". Reading the
    // attribute as content would make every absent scientific name the string " ".
    const [record] = [
      ...readRecords(
        `<TABLE><ALIM><alim_code> 1 </alim_code><alim_nom_sci missing=" " /></ALIM></TABLE>`,
        "ALIM",
      ),
    ];
    expect(record?.["alim_code"]).toBe("1");
    expect(record?.["alim_nom_sci"]).toBeNull();
  });

  test("decodes the entities the release actually uses", () => {
    expect(decodeEntities("Energy, N x Jones&apos; factor")).toBe("Energy, N x Jones' factor");
    expect(decodeEntities("&lt; 0,01")).toBe("< 0,01");
    expect(decodeEntities("a &amp; b")).toBe("a & b");
    expect(decodeEntities("&#233;")).toBe("é");
  });

  test("streams every record of a real table without holding them all", () => {
    const codes = [...readRecords(fixture("alim.fixture.xml"), "ALIM")].map((record) =>
      requireField(record, "alim_code", "ALIM"),
    );
    expect(codes).toContain("9100");
    expect(codes).toContain("36018");
    expect(new Set(codes).size).toBe(codes.length);
  });

  test("requireField throws rather than letting a shape change through", () => {
    const [record] = [...readRecords("<TABLE><ALIM><a> 1 </a></ALIM></TABLE>", "ALIM")];
    expect(() => requireField(record ?? {}, "alim_code", "ALIM")).toThrow(/missing required field/);
  });
});

describe("the encodings fixture, read end to end", () => {
  const rows = [...readRecords(fixture("encodings.fixture.xml"), "COMPO")];
  const teneurOf = (constCode: string) =>
    parseTeneur(rows.find((row) => row["const_code"] === constCode)?.["teneur"]);

  test("carries all four kinds through the XML layer", () => {
    expect(teneurOf("328").kind).toBe("value");
    expect(teneurOf("400").decimal).toBe("59.7");
    expect(teneurOf("25000").kind).toBe("notDetermined");
    expect(teneurOf("31000").kind).toBe("traces");
    // `&lt; 0,01` has to survive entity decoding before the grammar sees it.
    expect(teneurOf("40000").kind).toBe("lessThan");
    expect(teneurOf("34100").kind).toBe("value");
    expect(teneurOf("34100").decimal).toBe("0");
  });
});

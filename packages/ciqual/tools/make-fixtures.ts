// Run by hand to cut the committed fixtures out of the verified release.
//   bun run tools/make-fixtures.ts
import { writeFile } from "node:fs/promises";
import { join } from "node:path";
import { packageRoot, readSourceFile } from "../src/source";

const CODES = new Set([
  // PRD_FOOD 8.6's four pairs, both halves.
  "9100",
  "9104",
  "9810",
  "9811",
  "20535",
  "20589",
  "36017",
  "36018",
  // A pair the rescale test must refuse: fat was added, so composition changed.
  "4008",
  "4015",
  // Rows carrying the encodings the parser has to get right.
  "1000",
  "22000",
  "13039",
  "19593",
  "12726",
]);

const KEEP_CONST = new Set(["328", "25000", "31000", "40000", "34100", "400"]);
const KEEP_SUBGROUP = new Set([
  "0202",
  "0204",
  "0301",
  "0401",
  "0402",
  "0502",
  "0503",
  "0410",
  "0603",
  "0203",
]);

function blocks(xml: string, tag: string): string[] {
  const open = `<${tag}>`;
  const close = `</${tag}>`;
  const out: string[] = [];
  let cursor = xml.indexOf(open);
  while (cursor !== -1) {
    const end = xml.indexOf(close, cursor);
    if (end === -1) break;
    out.push(xml.slice(cursor, end + close.length));
    cursor = xml.indexOf(open, end + close.length);
  }
  return out;
}

function field(block: string, name: string): string | null {
  const match = block.match(new RegExp(`<${name}>([^<]*)</${name}>`));
  return match === null ? null : (match[1] as string).trim();
}

function table(kept: string[]): string {
  const body = kept.map((block) => `   ${block.replace(/\r\n/g, "\n").trim()}`).join("\n");
  return `<?xml version="1.0" encoding="utf-8" ?>\n<TABLE>\n${body}\n</TABLE>\n`;
}

const [alim, compo, grp] = await Promise.all([
  readSourceFile("alim_2025_11_03.xml"),
  readSourceFile("compo_2025_11_03.xml"),
  readSourceFile("alim_grp_2025_11_03.xml"),
]);

const dir = join(packageRoot, "fixtures");

await writeFile(
  join(dir, "alim.fixture.xml"),
  table(blocks(alim, "ALIM").filter((b) => CODES.has(field(b, "alim_code") ?? ""))),
  "utf8",
);

await writeFile(
  join(dir, "compo.fixture.xml"),
  table(
    blocks(compo, "COMPO").filter(
      (b) => CODES.has(field(b, "alim_code") ?? "") && KEEP_CONST.has(field(b, "const_code") ?? ""),
    ),
  ),
  "utf8",
);

await writeFile(
  join(dir, "alim_grp.fixture.xml"),
  table(
    blocks(grp, "ALIM_GRP").filter((b) => KEEP_SUBGROUP.has(field(b, "alim_ssgrp_code") ?? "")),
  ),
  "utf8",
);

console.log("fixtures written");

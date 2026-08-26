// The committed slice of Ciqual 2025 the tests run against.
//
// The real release is 71 MB and is not committed, so the tests cannot read it and must
// not depend on it. What is committed instead is a cut of the rows the tests actually
// assert on - both halves of PRD_FOOD 8.6's four pairs, one pair whose composition
// changed, and a handful of rows carrying the encodings the parser has to survive - taken
// from the release whose SHA-256 `ciqual.source.json` records, by
// `tools/make-fixtures.ts`.
//
// That is what makes the four ratios a regression test rather than a re-derivation: the
// numbers in the fixture are ANSES's numbers, frozen, and a change to the derivation
// moves them.

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { parseCiqualTable, type CiqualTable } from "./table";

const directory = join(import.meta.dir, "..", "fixtures");

export function fixture(name: string): string {
  const raw = readFileSync(join(directory, name), "utf8");
  return raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
}

let cached: CiqualTable | null = null;

export function fixtureTable(): CiqualTable {
  cached ??= parseCiqualTable({
    alim: fixture("alim.fixture.xml"),
    alimGrp: fixture("alim_grp.fixture.xml"),
    compo: fixture("compo.fixture.xml"),
  });
  return cached;
}

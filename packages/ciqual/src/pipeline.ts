// The whole generation, as one function, so that a test can run it on fixtures and the
// CLI can run it on the real archive without either owning the order of the steps.

import { buildCatalogue, serialiseCatalogue, type BuildResult } from "./catalogue";
import { DEFAULT_TOLERANCE, findPairs, type Pair, type RescaleTolerance } from "./pairing";
import { loadPortions } from "./portions";
import { formatReport } from "./report";
import { readSourceFile, requireVerifiedSource, sha256, sourceManifest } from "./source";
import { selectSubset, subsetOverrides, subsetRules, type SubsetResult } from "./subset";
import { parseCiqualTable, type CiqualTable } from "./table";

export interface SourceFiles {
  readonly alim: string;
  readonly alimGrp: string;
  readonly compo: string;
}

export interface Generated {
  readonly table: CiqualTable;
  readonly pairs: readonly Pair[];
  readonly subset: SubsetResult;
  readonly build: BuildResult;
  readonly json: string;
  readonly sha256: string;
  readonly report: string;
}

interface RulesWithCookedRatio {
  readonly cookedRatio?: {
    readonly relativeTolerance: number;
    readonly floor: RescaleTolerance["floor"];
    readonly gating: RescaleTolerance["gating"];
    readonly minimumDeviation: number;
  };
}

/**
 * The tolerance the rules file declares, or the built-in default when it says nothing.
 *
 * The rename is deliberate rather than sloppy: the JSON says `relativeTolerance` so that
 * it reads the same as the `clustering` block beside it, and the mapping happens here in
 * one place. It also has to happen: a missing field would make every comparison `NaN`,
 * and `NaN <= NaN` is false, so the silent failure mode is "no pair ever agrees" - a
 * catalogue with zero cooked ratios and no error to show for it.
 */
export function cookedRatioTolerance(): RescaleTolerance {
  const declared = (subsetRules as unknown as RulesWithCookedRatio).cookedRatio;
  if (declared === undefined) return DEFAULT_TOLERANCE;
  if (
    !Number.isFinite(declared.relativeTolerance) ||
    declared.floor === undefined ||
    declared.gating === undefined ||
    !Number.isFinite(declared.minimumDeviation)
  ) {
    throw new Error(
      "subset.rules.json: cookedRatio needs relativeTolerance, floor, gating and minimumDeviation",
    );
  }
  return {
    relative: declared.relativeTolerance,
    floor: declared.floor,
    gating: declared.gating,
    minimumDeviation: declared.minimumDeviation,
  };
}

export function generate(files: SourceFiles, version: string, archiveSha256: string): Generated {
  const table = parseCiqualTable(files);

  // PRD_FOOD 8.6 first, and over the *whole* table rather than over the subset: a ratio
  // is derived from the pair Ciqual contains, and pairing after the subset has already
  // dropped one half of it would silently lose ratios.
  const pairs = findPairs(table.foods.values(), cookedRatioTolerance());

  const subset = selectSubset(table.foods.values(), pairs, subsetRules, subsetOverrides);
  const portions = loadPortions();
  const build = buildCatalogue(version, subset.selected, portions);
  const json = serialiseCatalogue(build.catalogue);

  return {
    table,
    pairs,
    subset,
    build,
    json,
    sha256: sha256(json),
    report: formatReport({
      version,
      table,
      pairs,
      subset,
      build,
      rules: subsetRules,
      archiveSha256,
      catalogueSha256: sha256(json),
      bytes: Buffer.byteLength(json, "utf8"),
    }),
  };
}

/** Reads the verified archive off disk and generates. Refuses to run on a hash mismatch. */
export async function generateFromSource(): Promise<Generated> {
  const check = await requireVerifiedSource();
  const required = sourceManifest.files.filter((file) => file.readByBuild !== false);
  const byTag = new Map(required.map((file) => [file.recordTag, file.fileName]));

  const [alim, alimGrp, compo] = await Promise.all([
    readSourceFile(byTag.get("ALIM") as string),
    readSourceFile(byTag.get("ALIM_GRP") as string),
    readSourceFile(byTag.get("COMPO") as string),
  ]);

  return generate(
    { alim, alimGrp, compo },
    sourceManifest.version,
    check.actualArchiveSha256 as string,
  );
}

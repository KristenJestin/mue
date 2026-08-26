// The Ciqual tables, reduced to what PRD_FOOD 9.1 keeps.
//
// Ciqual 2025 documents 3 484 foods against 74 constituents. Mue uses five of them —
// energy, protein, carbohydrate, fat, fibre — plus water, which is read here and never
// shipped, because it is what makes PRD_FOOD 8.6's cooked ratio derivable.

import { parseTeneur, teneurToNumber, teneurToThousandths, type Teneur } from "./teneur";
import { readRecords, requireField, type XmlRecord } from "./xml";

/** The six `const_code` values this package reads out of Ciqual's seventy-four. */
export const CONSTITUENTS = {
  /** `Energy, Regulation EU No 1169/2011 (kcal/100g)`. */
  energy: "328",
  /** `Protein (g/100g)` — the declared value, not the N × 6.25 estimate (25003). */
  protein: "25000",
  carbs: "31000",
  fat: "40000",
  fibre: "34100",
  /** Read to derive the cooked ratio; PRD_FOOD 9.1 keeps five constituents, not six. */
  water: "400",
} as const;

export type ConstituentName = keyof typeof CONSTITUENTS;

export const SHIPPED_CONSTITUENTS = ["energy", "protein", "carbs", "fat", "fibre"] as const;

export type ShippedConstituent = (typeof SHIPPED_CONSTITUENTS)[number];

export type Composition = Readonly<Record<ConstituentName, Teneur | undefined>>;

export interface CiqualFood {
  readonly code: string;
  readonly nameFr: string;
  /**
   * `alim_nom_eng`. The app is English-only (PRD_FOOD 1) and PRD_FOOD 5 puts
   * translating the catalogue out of scope, so this field is the whole reason a
   * Ciqual-backed catalogue is affordable at all.
   */
  readonly nameEng: string;
  readonly groupCode: string;
  readonly subGroupCode: string;
  readonly subSubGroupCode: string;
  readonly composition: Composition;
}

export interface CiqualGroup {
  readonly groupCode: string;
  readonly groupNameEng: string;
  readonly subGroupCode: string;
  readonly subGroupNameEng: string;
  readonly subSubGroupCode: string;
  readonly subSubGroupNameEng: string;
}

export interface TeneurCounts {
  readonly value: number;
  readonly notDetermined: number;
  readonly traces: number;
  readonly lessThan: number;
}

export interface CiqualTable {
  readonly foods: ReadonlyMap<string, CiqualFood>;
  readonly groups: ReadonlyMap<string, CiqualGroup>;
  readonly groupNames: ReadonlyMap<string, string>;
  readonly subGroupNames: ReadonlyMap<string, string>;
  /** How every cell of the six kept constituents was encoded, for the build report. */
  readonly counts: TeneurCounts;
}

function compositionIndex(compoXml: string): {
  index: Map<string, Record<string, Teneur>>;
  counts: TeneurCounts;
} {
  const wanted = new Map<string, ConstituentName>(
    Object.entries(CONSTITUENTS).map(([name, code]) => [code, name as ConstituentName]),
  );
  const index = new Map<string, Record<string, Teneur>>();
  let value = 0;
  let notDetermined = 0;
  let traces = 0;
  let lessThan = 0;

  for (const record of readRecords(compoXml, "COMPO")) {
    const constCode = record["const_code"];
    const name = constCode === null || constCode === undefined ? undefined : wanted.get(constCode);
    if (name === undefined) continue;

    const alimCode = requireField(record, "alim_code", "COMPO");
    const teneur = parseTeneur(record["teneur"]);
    if (teneur.kind === "value") value += 1;
    else if (teneur.kind === "traces") traces += 1;
    else if (teneur.kind === "lessThan") lessThan += 1;
    else notDetermined += 1;

    const row = index.get(alimCode) ?? {};
    row[name] = teneur;
    index.set(alimCode, row);
  }

  return { index, counts: { value, notDetermined, traces, lessThan } };
}

export function parseCiqualTable(files: {
  alim: string;
  alimGrp: string;
  compo: string;
}): CiqualTable {
  const { index, counts } = compositionIndex(files.compo);

  const groups = new Map<string, CiqualGroup>();
  const groupNames = new Map<string, string>();
  const subGroupNames = new Map<string, string>();
  for (const record of readRecords(files.alimGrp, "ALIM_GRP")) {
    const group: CiqualGroup = {
      groupCode: requireField(record, "alim_grp_code", "ALIM_GRP"),
      groupNameEng: requireField(record, "alim_grp_nom_eng", "ALIM_GRP"),
      subGroupCode: requireField(record, "alim_ssgrp_code", "ALIM_GRP"),
      subGroupNameEng: requireField(record, "alim_ssgrp_nom_eng", "ALIM_GRP"),
      subSubGroupCode: record["alim_ssssgrp_code"] ?? "000000",
      subSubGroupNameEng: record["alim_ssssgrp_nom_eng"] ?? "-",
    };
    groups.set(`${group.groupCode}/${group.subGroupCode}/${group.subSubGroupCode}`, group);
    groupNames.set(group.groupCode, group.groupNameEng);
    subGroupNames.set(group.subGroupCode, group.subGroupNameEng);
  }

  const foods = new Map<string, CiqualFood>();
  for (const record of readRecords(files.alim, "ALIM")) {
    const code = requireField(record, "alim_code", "ALIM");
    const nameEng = record["alim_nom_eng"];
    if (nameEng === null || nameEng === undefined) {
      // Not a row to skip quietly: PRD_FOOD 5 rules out translating the catalogue, so a
      // food without an English name has no affordable path into an English-only app.
      throw new Error(`Ciqual food ${code} has no alim_nom_eng; the release changed shape.`);
    }
    foods.set(code, {
      code,
      nameFr: requireField(record, "alim_nom_fr", "ALIM"),
      nameEng,
      groupCode: requireField(record, "alim_grp_code", "ALIM"),
      subGroupCode: requireField(record, "alim_ssgrp_code", "ALIM"),
      subSubGroupCode: record["alim_ssssgrp_code"] ?? "000000",
      composition: (index.get(code) ?? {}) as Composition,
    });
  }

  return { foods, groups, groupNames, subGroupNames, counts };
}

/** A constituent as a canonical integer, or `null` for any of Ciqual's three unknowns. */
export function thousandths(food: CiqualFood, name: ConstituentName): number | null {
  const teneur = food.composition[name];
  return teneur === undefined ? null : teneurToThousandths(teneur);
}

/** The same value as a float, used only by the ratio arithmetic. */
export function asNumber(food: CiqualFood, name: ConstituentName): number | null {
  const teneur = food.composition[name];
  return teneur === undefined ? null : teneurToNumber(teneur);
}

export function teneurKindOf(record: XmlRecord): Teneur {
  return parseTeneur(record["teneur"]);
}

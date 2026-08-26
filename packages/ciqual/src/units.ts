// PRD_FOOD 15's bounds and this module's canonical units, restated on the generator
// side of the asset.
//
// These constants are duplicated from `FoodUnits.kt` on purpose: `packages/ciqual`
// imports nothing, and the Android module is not a TypeScript dependency. The
// duplication is held honest by `catalogue.test.ts`, which asserts every emitted row
// against them, and by the Kotlin `CiqualEntry.toFoodOrNull` which applies the same
// ranges to the same file from the other side.

/** Thousandths of a kilocalorie, thousandths of a gram: the module's canonical units. */
export const THOUSANDTHS_PER_UNIT = 1_000;

/** PRD_FOOD 15: 0 to 900 kcal per 100 g. Pure fat is 900, so nothing edible exceeds it. */
export const ENERGY_PER_100_RANGE = { min: 0, max: 900_000 } as const;

/** PRD_FOOD 15: 0 to 100 g per 100 g. */
export const MACRO_PER_100_RANGE = { min: 0, max: 100_000 } as const;

/** PRD_FOOD 15: strictly positive, 0.3 to 5. */
export const COOKED_RATIO_RANGE = { min: 300, max: 5_000 } as const;

/** PRD_FOOD 15: a usual serving weighs 1 to 2 000 g or ml. */
export const USUAL_SERVING_RANGE = { min: 1_000, max: 2_000_000 } as const;

/** PRD_FOOD 15: 1 to 80 characters once whitespace is cleaned up. */
export const NAME_LENGTH_RANGE = { min: 1, max: 80 } as const;

export interface Range {
  readonly min: number;
  readonly max: number;
}

export function isInRange(value: number, range: Range): boolean {
  return Number.isInteger(value) && value >= range.min && value <= range.max;
}

export function absentOrIn(value: number | null | undefined, range: Range): boolean {
  return value === null || value === undefined || isInRange(value, range);
}

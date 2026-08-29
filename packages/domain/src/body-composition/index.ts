// PRD_SCALE 13.2 on the server: the `mue-foot-to-foot-v1` formula set, its domain of
// validity and its arithmetic. PRD_SCALE 22 has the server recalculate a composition and
// refuse any formula version it does not know, and PRD section 20.2 says the rule is
// implemented once — sync handlers and MCP tools call these functions and never redo the
// arithmetic themselves.
//
// The parity PRD_SCALE 23 asks for is not asserted here, it is tested: this module and
// Android's `BodyCompositionCalculator` both replay
// `apps/android/app/src/test/resources/bodycomposition/mue-foot-to-foot-v1.json`, the same
// file, from `body-composition.test.ts` and `BodyCompositionCalculatorTest` respectively.

export {
  PLAUSIBILITY_CHECKS,
  PROFILE_INPUTS,
  type BodyComposition,
  type BodyCompositionInput,
  type BodyCompositionOutcome,
  type BodyCompositionResult,
  type PlausibilityCheck,
  type ProfileInput,
  calculateBodyComposition,
  compositionOrNull,
  recalculateBodyComposition,
} from "./calculator";
export {
  FORMULA_ID,
  FORMULA_VERSION,
  MAX_AGE_YEARS,
  MAX_BMI,
  MAX_HEIGHT_CM,
  MIN_AGE_YEARS,
  MIN_BMI,
  MIN_HEIGHT_CM,
  type Sex,
  bmiOrNull,
  isAgeInDomain,
  isBmiInDomain,
  isHeightUsable,
  isImpedanceUsable,
  isKnownFormula,
  isSex,
} from "./formula";
export { WORKING_SCALE, formatWorking } from "./decimal";

/**
 * The spacing scale. Shared by the Web UI and, later, the Android theme, which is
 * why the values are unitless-by-convention strings rather than platform types.
 */
export const spacing = {
  xs: "0.25rem",
  sm: "0.5rem",
  md: "1rem",
  lg: "1.5rem",
  xl: "2rem",
} as const;

export type SpacingToken = keyof typeof spacing;

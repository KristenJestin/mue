import { type SpacingToken, spacing } from "@mue/design-tokens";
import type { CSSProperties } from "react";

/** The single place the shared spacing scale becomes CSS, so tokens cannot drift into literals. */
export function stackStyle(gap: SpacingToken): CSSProperties {
  return { display: "flex", flexDirection: "column", gap: spacing[gap] };
}
